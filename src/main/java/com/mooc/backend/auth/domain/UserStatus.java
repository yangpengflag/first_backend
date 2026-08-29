package com.mooc.backend.auth.domain;

/**
 * 用户生命周期状态四态。
 *
 * <p>与登录响应码的映射（见 openspec/specs/auth-module/spec.md）：
 * <pre>
 *   ACTIVE           → 200 OK
 *   LOCKED           → 423 Locked
 *   DELETED          → 401 Unauthorized
 *   EMAIL_UNVERIFIED → 403 Forbidden
 * </pre>
 */
public enum UserStatus {

    /** 已激活，可正常登录与访问。 */
    ACTIVE,

    /** 被锁定（连续失败达阈值 或 管理员锁定），锁定期内拒绝登录。 */
    LOCKED,

    /** 已软删除：行保留，deletedAt 非空，不可自行恢复。 */
    DELETED,

    /** 已注册但邮箱未验证，登录返回 403。 */
    EMAIL_UNVERIFIED
}
