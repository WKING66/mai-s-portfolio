package dev.amai.portfolio.system.manager;

import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.PasswordCipherConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
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
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.springframework.stereotype.Component;

/**
 * 账号凭据处理：允许来源、固定 RSA 解密、新密码规则与散列。
 * 登录保留原密码字符，只有注册和改密规范化新密码；数据库与会话生命周期由业务 Service 管理。
 * 私钥启动时加载，Cipher 按调用创建；绝不将明文密码保存到成员变量或日志中。
 */
@Component
public class CredentialManager {
    private static final int RSA_BITS = PasswordCipherConstants.RSA_BITS;
    private static final int CIPHERTEXT_BYTES = PasswordCipherConstants.CIPHERTEXT_BYTES;
    private static final int CIPHERTEXT_BASE64_LENGTH = 4 * ((CIPHERTEXT_BYTES + 2) / 3);
    private static final int MAX_KEY_FILE_BYTES = 16 * 1024;
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final int MIN_CHARACTERS = 12;
    private static final Pattern PADDING = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    private static final Pattern CHARACTERS = Pattern.compile("[\\x21-\\x7E]+");

    private final RSAPrivateKey rsaPrivateKey;
    private final SecurityProperties securityProperties;
    private final PasswordHasher passwordHasher;

    public CredentialManager(SecurityProperties securityProperties, PasswordHasher passwordHasher) {
        this.securityProperties = securityProperties;
        this.passwordHasher = passwordHasher;
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

    /**
     * 注册和改密使用同一套新密码规则：只去除首尾空白，内部只允许可见 ASCII，长度 12 至 190 字节。
     * 登录不能调用此方法，否则会改变已有账号密码的实际匹配语义。
     */
    public String normalizeNewPassword(String value) {
        String password = value == null ? "" : PADDING.matcher(value).replaceAll("");
        if (!password.isEmpty() && !CHARACTERS.matcher(password).matches()) {
            throw invalid(SystemMessageConstants.REGISTER_PASSWORD_CHARACTERS_INVALID);
        }
        if (password.codePointCount(0, password.length()) < MIN_CHARACTERS
                || password.getBytes(StandardCharsets.UTF_8).length
                        > PasswordCipherConstants.MAX_PLAINTEXT_BYTES) {
            throw invalid(SystemMessageConstants.REGISTER_PASSWORD_INVALID);
        }
        return password;
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }

    /** 登录与注册只接受配置允许的页面来源；拒绝时不解密密码、不读取账号。 */
    public void requireAllowedOrigin(String origin) {
        if (origin == null || !securityProperties.allowedOrigins().contains(origin)) {
            throw new ApiException(ApiErrorCode.ORIGIN_INVALID, SystemMessageConstants.ORIGIN_INVALID);
        }
    }

    /** 只处理密码散列，不查询账号，也不在此建立或注销会话。 */
    public String encode(String password) {
        return passwordHasher.encode(password);
    }

    public boolean matches(String password, String hash) {
        return passwordHasher.matches(password, hash);
    }
}
