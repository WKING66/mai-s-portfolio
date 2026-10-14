package dev.amai.portfolio.datasource;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.web.WebMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将数据库兜底约束异常转换为稳定响应；业务校验仍必须在 Service 完成。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class DataAccessExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(DataAccessExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<R<Void>> handleDataConflict(DataIntegrityViolationException error) {
        LOG.error("Unexpected database integrity violation", error);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(R.failure(
            ApiErrorCode.DATA_CONFLICT.name(), WebMessageConstants.DATA_CONFLICT, List.of()));
    }
}
