package dev.amai.portfolio;

import java.nio.file.*;
import java.security.*;
import java.util.Base64;

public class GenerateRsaKey {

    public static void main(String[] args) throws Exception {

        Path dir = Paths.get("F:/secrets/mai-portfolio/auth");
        Files.createDirectories(dir);

        // 1. 生成 RSA-2048 密钥对
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);

        KeyPair keyPair = generator.generateKeyPair();

        // 2. 私钥：PKCS#8 DER -> PEM
        byte[] privateDer = keyPair.getPrivate().getEncoded();

        String privatePem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(privateDer)
                        + "\n-----END PRIVATE KEY-----\n";

        Files.writeString(
                dir.resolve("private.pem"),
                privatePem
        );

        // 3. 公钥：X.509 SubjectPublicKeyInfo / SPKI DER
        byte[] publicDer = keyPair.getPublic().getEncoded();

        Files.write(
                dir.resolve("public.der"),
                publicDer
        );

        // 4. 前端需要的 Base64 DER/SPKI
        String publicBase64 =
                Base64.getEncoder().encodeToString(publicDer);

        System.out.println("密钥生成完成：");
        System.out.println();
        System.out.println("Private:");
        System.out.println(dir.resolve("private.pem"));
        System.out.println();
        System.out.println("Public DER:");
        System.out.println(dir.resolve("public.der"));
        System.out.println();
        System.out.println("NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY=");
        System.out.println(publicBase64);
    }
}