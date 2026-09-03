package com.mooc.backend.auth.domain;

/**
 * 用户角色。
 *
 * <p>{@code USER} 普通用户、{@code ADMIN} 管理员。管理员的典型能力是删除任意用户评论
 * （见 comments / places 的评论删除逻辑）。角色仅由受信任的管理通道（如数据库直更）
 * 提升，注册流程与公开端点不暴露赋值入口，避免权限自提升。
 */
public enum Role {
    USER,
    ADMIN
}
