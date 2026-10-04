package dev.amai.portfolio;

import java.nio.charset.StandardCharsets;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import tools.jackson.databind.json.JsonMapper;

/** 使用测试配置中的固定公钥，验证 Java 密文与浏览器 Web Crypto 的 OAEP 参数一致。 */
final class LoginTestClient {
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private LoginTestClient() {
    }

    static String encryptedLoginBody(JsonMapper json, String username, String password)
            throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, AuthKeyTestSupport.KEY_PAIR.getPublic(), OAEP_SHA256);
        String encryptedPassword = Base64.getEncoder().encodeToString(
            cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        return json.writeValueAsString(Map.of("username", username,
            "encryptedPassword", encryptedPassword));
    }
}
