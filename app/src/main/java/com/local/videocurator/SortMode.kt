package com.local.videocurator

enum class SortMode(val label: String) {
    MANUAL("手动排序"),
    NAME_ASC("名称 A-Z"),
    NAME_DESC("名称 Z-A"),
    RATING_DESC("评分从高到低"),
    RATING_ASC("评分从低到高"),
    DURATION_DESC("时长从长到短"),
    DURATION_ASC("时长从短到长"),
    MODIFIED_DESC("最近修改优先"),
    MODIFIED_ASC("最早修改优先");

    companion object {
        fun fromName(value: String?): SortMode = values().firstOrNull { it.name == value } ?: MANUAL
    }
}
