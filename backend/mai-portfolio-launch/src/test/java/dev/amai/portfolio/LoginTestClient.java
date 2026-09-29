package dev.amai.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.system.api.AuthConstants;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 真实走一次 challenge 接口，验证 Java 密文与浏览器 Web Crypto 的 OAEP 参数一致。 */
final class LoginTestClient {
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private LoginTestClient() {
    }

    static String encryptedLoginBody(MockMvc mvc, JsonMapper json, String username, String password)
            throws Exception {
        byte[] response = mvc.perform(get(AuthConstants.LOGIN_CHALLENGE_PATH))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode challenge = json.readTree(response).path("data");
        byte[] encodedKey = Base64.getDecoder().decode(challenge.path("publicKey").asText());
        PublicKey publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(encodedKey));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256);
        String encryptedPassword = Base64.getEncoder().encodeToString(
            cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        return json.writeValueAsString(Map.of("username", username,
            "challengeId", challenge.path("challengeId").asText(),
            "encryptedPassword", encryptedPassword));
    }
}
