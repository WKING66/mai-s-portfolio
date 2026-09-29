package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.entity.vo.LoginChallengeVo;

public interface LoginChallengeService {
    /** 签发短时、一次性 RSA-OAEP 公钥；私钥只保留在服务端内存。 */
    LoginChallengeVo issueChallenge(String clientKey);

    /** 原子消费凭证后解密密码；失败和重放均不能再次使用该凭证。 */
    String consumePassword(String clientKey, String challengeId, String encryptedPassword);
}
