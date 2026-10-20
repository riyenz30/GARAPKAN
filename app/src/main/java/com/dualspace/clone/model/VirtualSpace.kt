package com.dualspace.clone.model

data class InstanceAppItem(
    val packageName: String,
    val label: String,
    val userId: Int,
    val isSystemGoogle: Boolean = false,
    val cloneRef: Clone? = null
)

data class VirtualSpace(
    val userId: Int,
    val spaceNumber: Int,
    val apps: List<InstanceAppItem>
)
