package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.springframework.stereotype.Service;

/** 启动时加载固定私钥，每次解密创建独立 Cipher，避免并发复用非线程安全对象。 */
@Service
public class PasswordCryptoServiceImpl implements PasswordCryptoService {
    private static final int RSA_BITS = 2048;
    private static final int CIPHERTEXT_BYTES = RSA_BITS / Byte.SIZE;
    private static final int CIPHERTEXT_BASE64_LENGTH = 344;
    private static final int MAX_KEY_FILE_BYTES = 16 * 1024;
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private final RSAPrivateKey privateKey;

    public PasswordCryptoServiceImpl(SecurityProperties security) {
        byte[] encoded = null;
        byte[] keyFile = null;
        try (var input = security.rsaPrivateKey().getInputStream()) {
            keyFile = input.readNBytes(MAX_KEY_FILE_BYTES + 1);
            if (keyFile.length > MAX_KEY_FILE_BYTES) {
                throw new IllegalArgumentException();
            }
            String pem = new String(keyFile, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            encoded = Base64.getDecoder().decode(pem);
            privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(encoded));
            if (privateKey.getModulus().bitLength() != RSA_BITS) {
                throw new IllegalArgumentException();
            }
        } catch (IOException | GeneralSecurityException | IllegalArgumentException | ClassCastException error) {
            // 不输出私钥内容；错误配置直接阻止启动，禁止回退到临时生成的密钥。
            throw new IllegalStateException(SystemMessageConstants.LOGIN_PRIVATE_KEY_INVALID);
        } finally {
            if (encoded != null) Arrays.fill(encoded, (byte) 0);
            if (keyFile != null) Arrays.fill(keyFile, (byte) 0);
        }
    }

    @Override
    public String decryptPassword(String encryptedPassword) {
        byte[] plaintext = null;
        try {
            if (encryptedPassword == null || encryptedPassword.length() != CIPHERTEXT_BASE64_LENGTH) {
                throw new IllegalArgumentException();
            }
            byte[] ciphertext = Base64.getDecoder().decode(encryptedPassword);
            if (ciphertext.length != CIPHERTEXT_BYTES) {
                throw new IllegalArgumentException();
            }
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_SHA256);
            plaintext = cipher.doFinal(ciphertext);
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext)).toString();
        } catch (GeneralSecurityException | IllegalArgumentException | CharacterCodingException error) {
            throw new ApiException(ApiErrorCode.AUTH_INVALID_CREDENTIAL_PAYLOAD,
                SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID);
        } finally {
            // JVM 无法保证立即清除 String；清理解密产生的字节副本，不缓存密码。
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }
}
