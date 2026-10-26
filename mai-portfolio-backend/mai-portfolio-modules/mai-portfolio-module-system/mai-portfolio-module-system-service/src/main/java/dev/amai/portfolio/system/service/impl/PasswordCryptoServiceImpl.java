package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.PasswordCipherConstants;
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
    private static final int RSA_BITS = PasswordCipherConstants.RSA_BITS;
    private static final int CIPHERTEXT_BYTES = PasswordCipherConstants.CIPHERTEXT_BYTES;
    private static final int CIPHERTEXT_BASE64_LENGTH = 4 * ((CIPHERTEXT_BYTES + 2) / 3);
    private static final int MAX_KEY_FILE_BYTES = 16 * 1024;
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private final RSAPrivateKey rsaPrivateKey;

    public PasswordCryptoServiceImpl(SecurityProperties securityProperties) {
        byte[] encodedPrivateKey = null;
        byte[] privateKeyFileBytes = null;
        try (var input = securityProperties.rsaPrivateKey().getInputStream()) {
            // 1. 有界读取外部 PKCS#8 私钥，并校验 RSA 位数，不生成替代密钥。
            privateKeyFileBytes = input.readNBytes(MAX_KEY_FILE_BYTES + 1);
            if (privateKeyFileBytes.length > MAX_KEY_FILE_BYTES) {
                throw new IllegalArgumentException();
            }
            String pem =
                    new String(privateKeyFileBytes, StandardCharsets.US_ASCII)
                            .replace("-----BEGIN PRIVATE KEY-----", "")
                            .replace("-----END PRIVATE KEY-----", "")
                            .replaceAll("\\s", "");
            encodedPrivateKey = Base64.getDecoder().decode(pem);
            rsaPrivateKey =
                    (RSAPrivateKey)
                            KeyFactory.getInstance("RSA")
                                    .generatePrivate(new PKCS8EncodedKeySpec(encodedPrivateKey));
            if (rsaPrivateKey.getModulus().bitLength() != RSA_BITS) {
                throw new IllegalArgumentException();
            }
        } catch (IOException
                | GeneralSecurityException
                | IllegalArgumentException
                | ClassCastException error) {
            // 不输出私钥内容；错误配置直接阻止启动，禁止回退到临时生成的密钥。
            throw new IllegalStateException(SystemMessageConstants.LOGIN_PRIVATE_KEY_INVALID);
        } finally {
            if (encodedPrivateKey != null) {
                Arrays.fill(encodedPrivateKey, (byte) 0);
            }
            if (privateKeyFileBytes != null) {
                Arrays.fill(privateKeyFileBytes, (byte) 0);
            }
        }
    }

    @Override
    public String decryptPassword(String encryptedPassword) {
        byte[] plaintext = null;
        try {
            // 1. 校验 Base64 与固定密文长度，拒绝明文及不完整密文。
            if (encryptedPassword == null
                    || encryptedPassword.length() != CIPHERTEXT_BASE64_LENGTH) {
                throw new IllegalArgumentException();
            }
            byte[] ciphertext = Base64.getDecoder().decode(encryptedPassword);
            if (ciphertext.length != CIPHERTEXT_BYTES) {
                throw new IllegalArgumentException();
            }
            // 2. 每次请求创建独立 Cipher，使用固定 OAEP 参数解密。
            Cipher passwordCipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            passwordCipher.init(Cipher.DECRYPT_MODE, rsaPrivateKey, OAEP_SHA256);
            plaintext = passwordCipher.doFinal(ciphertext);
            // 3. 严格按 UTF-8 解码；任一步失败均返回同一安全提示，不暴露密码或私钥。
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext))
                    .toString();
        } catch (GeneralSecurityException
                | IllegalArgumentException
                | CharacterCodingException error) {
            throw new ApiException(
                    ApiErrorCode.AUTH_INVALID_CREDENTIAL_PAYLOAD,
                    SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID);
        } finally {
            // JVM 无法保证立即清除 String；清理解密产生的字节副本，不缓存密码。
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }
}
