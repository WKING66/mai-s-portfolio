package dev.amai.portfolio.blog.entity.domain;

import java.io.IOException;
import java.io.OutputStream;

/** 授权与资源检查完成后的输出计划；Writer 在异步输出线程执行，不再访问登录线程上下文。 */
public record DocumentExport(String filename, String contentType, Writer writer) {
    @FunctionalInterface
    public interface Writer {
        void write(OutputStream output) throws IOException;
    }
}
