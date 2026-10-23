package dev.amai.portfolio.portfolio.entity.request;

import dev.amai.portfolio.common.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;

/** 项目专有的视图和状态筛选；分页字段与校验继承公共请求。 */
@Schema(description = "项目列表筛选；view 缺省 PUBLIC，status 仅供 MANAGE")
public class ProjectListRequest extends PageRequest {
    @Schema(description = "列表视图；MANAGE 要求站长权限", allowableValues = {"PUBLIC", "MANAGE"})
    private String view;

    @Schema(description = "仅 MANAGE 可用", allowableValues = {"DRAFT", "PUBLISHED"})
    private String status;

    public ProjectListRequest() {
    }

    public ProjectListRequest(String view, int page, Integer size, String status) {
        super(page, size);
        this.view = view;
        this.status = status;
    }

    public String getView() {
        return view;
    }

    public void setView(String view) {
        this.view = view;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
