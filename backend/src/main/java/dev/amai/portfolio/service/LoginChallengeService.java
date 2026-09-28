package dev.amai.portfolio.service;

import dev.amai.portfolio.dto.LoginChallengeResponse;

public interface LoginChallengeService {
    /** 签发短时、一次性 RSA-OAEP 公钥；私钥只保留在服务端内存。 */
    LoginChallengeResponse issueChallenge();

    /** 原子消费凭证后解密密码；失败和重放均不能再次使用该凭证。 */
    String consumePassword(String challengeId, String encryptedPassword);
}
