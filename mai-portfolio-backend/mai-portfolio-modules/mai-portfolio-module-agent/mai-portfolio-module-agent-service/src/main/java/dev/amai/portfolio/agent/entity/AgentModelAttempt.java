package dev.amai.portfolio.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("agent_model_attempt")
public class AgentModelAttempt {
    @TableId(type = IdType.INPUT)
    private String id;

    private LocalDate billingDay;

    private String currency;

    private String priceVersion;

    private BigDecimal inputPrice;

    private BigDecimal outputPrice;

    private int inputLimit;

    private int outputLimit;

    private BigDecimal ceiling;

    private BigDecimal actual;

    private boolean boundsExceeded;
}
