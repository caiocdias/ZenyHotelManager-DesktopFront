package com.zeny.desktop.seed.cli

/** Valor sigiloso (senha ou PIN): o `toString` nunca o revela. */
class Secret(
    val value: String,
) {
    override fun toString() = "***"

    override fun equals(other: Any?) = other is Secret && other.value == value

    override fun hashCode() = value.hashCode()
}
