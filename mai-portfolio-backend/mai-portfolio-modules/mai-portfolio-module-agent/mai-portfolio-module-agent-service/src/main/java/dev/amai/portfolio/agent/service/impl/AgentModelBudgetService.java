package dev.amai.portfolio.agent.service.impl;

import dev.amai.portfolio.agent.config.AgentModelBudgetProperties;
import dev.amai.portfolio.agent.entity.AgentModelAttempt;
import dev.amai.portfolio.agent.entity.request.ModelBudgetReservation;
import dev.amai.portfolio.agent.entity.request.ModelBudgetSettlement;
import dev.amai.portfolio.agent.mapper.AgentModelBudgetMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentModelBudgetService {
    private static final long MODEL_BUDGET_LOCK = 7137426010005L;
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MONEY_SCALE = 6;

    private final AgentModelBudgetMapper agentModelBudgetMapper;

    private final AgentModelBudgetProperties agentModelBudgetProperties;

    public AgentModelBudgetService(AgentModelBudgetMapper agentModelBudgetMapper,
            AgentModelBudgetProperties agentModelBudgetProperties) {
        this.agentModelBudgetMapper = agentModelBudgetMapper;
        this.agentModelBudgetProperties = agentModelBudgetProperties;
    }

    /** 在发起供应商调用前记录一次金额预占，任何拒绝都不得触发模型外呼。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reserve(ModelBudgetReservation request) {
        var policy = agentModelBudgetProperties;
        String currency = policy.currency();
        // 缺少同币种的模型报价或停用金额预算时，无法安全计算本次调用上界。
        if (!currency.equals(request.currency()) || (!policy.unlimited() && policy.dailyLimit().signum() == 0)
                || (request.outputLimit() > 0 && request.outputPrice().signum() == 0)) {
            throw unavailable();
        }
        BigDecimal ceiling = cost(request.inputPrice(), request.outputPrice(),
                request.inputLimit(), request.outputLimit());
        agentModelBudgetMapper.lockBudget(MODEL_BUDGET_LOCK);
        LocalDate day = LocalDate.now(BILLING_ZONE);
        var legacy = request.legacyUsage();
        var totals = agentModelBudgetMapper.totals(day, currency);
        // 旧账本和新账本共用额度，跨午夜的旧统计失效。币种冲突或用量超界时暂停外呼。
        if (!day.equals(legacy.billingDay()) || legacy.blocked() || totals.blocked()) {
            throw unavailable();
        }
        BigDecimal occupied = totals.occupied().add(legacy.occupied()).add(ceiling);
        if (!policy.unlimited() && occupied.compareTo(policy.dailyLimit()) > 0) {
            throw unavailable();
        }
        String attemptId = request.attemptId().toLowerCase(Locale.ROOT);
        // 同一尝试最多允许一次外呼。撤销先于预占到达时，取消记录会拦住迟到的预占。
        if (agentModelBudgetMapper.wasCancelled(attemptId)
                || agentModelBudgetMapper.selectById(attemptId) != null) {
            throw unavailable();
        }
        var attempt = new AgentModelAttempt();
        attempt.setId(attemptId);
        attempt.setBillingDay(day);
        attempt.setCurrency(currency);
        attempt.setPriceVersion(request.priceVersion());
        attempt.setInputPrice(request.inputPrice());
        attempt.setOutputPrice(request.outputPrice());
        attempt.setInputLimit(request.inputLimit());
        attempt.setOutputLimit(request.outputLimit());
        attempt.setCeiling(ceiling);
        agentModelBudgetMapper.insert(attempt);
    }

    /** 仅释放执行服务确认尚未外呼的尝试，并拦住迟到的同编号预占。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void release(String attemptId) {
        String normalizedId = attemptId.toLowerCase(Locale.ROOT);
        agentModelBudgetMapper.lockBudget(MODEL_BUDGET_LOCK);
        var attempt = agentModelBudgetMapper.selectById(normalizedId);
        if (attempt != null && (attempt.getActual() != null || attempt.isBoundsExceeded())) {
            // 已结算或已发现超界证明供应商已被调用，保留账本和限制。
            return;
        }
        agentModelBudgetMapper.markCancelled(normalizedId);
        if (attempt != null) {
            agentModelBudgetMapper.deleteById(normalizedId);
        }
    }

    /** 有可信用量时按预占时的价格结算，超界后封锁后续调用。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean settle(ModelBudgetSettlement request) {
        agentModelBudgetMapper.lockBudget(MODEL_BUDGET_LOCK);
        var attempt = agentModelBudgetMapper.selectById(request.attemptId().toLowerCase(Locale.ROOT));
        if (attempt == null) {
            throw unavailable();
        }
        if (attempt.isBoundsExceeded()) {
            return false;
        }
        // 没有可信用量时保留预占，重复回报也不能覆盖第一次结算结果。
        if (attempt.getActual() != null || (request.inputTokens() == 0 && request.outputTokens() == 0)) {
            return true;
        }
        if (request.inputTokens() > attempt.getInputLimit() || request.outputTokens() > attempt.getOutputLimit()) {
            attempt.setBoundsExceeded(true);
            agentModelBudgetMapper.updateById(attempt);
            return false;
        }
        attempt.setActual(cost(attempt.getInputPrice(), attempt.getOutputPrice(),
                request.inputTokens(), request.outputTokens()));
        agentModelBudgetMapper.updateById(attempt);
        return true;
    }

    private static BigDecimal cost(BigDecimal inputPrice, BigDecimal outputPrice, int input, int output) {
        return inputPrice.multiply(BigDecimal.valueOf(input))
                .add(outputPrice.multiply(BigDecimal.valueOf(output)))
                .movePointLeft(MONEY_SCALE)
                .setScale(MONEY_SCALE, RoundingMode.CEILING);
    }

    private static ApiException unavailable() {
        return new ApiException(ApiErrorCode.RATE_LIMITED, "模型费用预算或定价暂不可用。");
    }
}
