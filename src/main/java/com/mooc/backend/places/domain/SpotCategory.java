package com.mooc.backend.places.domain;

/**
 * 景点分类枚举。持久化为英文常量（DB 存 {@code nature} 等），响应序列化保持 snake_case 小写即可（值即枚举名）。
 */
public enum SpotCategory {

    NATURE,
    CULTURE,
    HISTORY,
    FOOD,
    DISTRICT,
    LEISURE
}
