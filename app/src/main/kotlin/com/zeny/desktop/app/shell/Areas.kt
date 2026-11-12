@file:Suppress("MatchingDeclarationName") // o plano nomeia o arquivo Areas.kt; ele agrupa Area e as funções

package com.zeny.desktop.app.shell

/** Áreas do menu lateral; cada uma aparece com qualquer uma das suas permissões. */
enum class Area(
    val id: String,
    val label: String,
    val permissions: Set<String>,
) {
    RECEPTION("recepcao", "Recepção", setOf("frontdesk.stays.checkin", "frontdesk.stays.checkout")),
    RESERVATIONS("reservas", "Reservas", setOf("reservations.reservations.read")),
    ACCOUNTS("contas", "Contas e caixa", setOf("billing.folios.read", "billing.cashier.operate")),
    GUESTS("hospedes", "Hóspedes", setOf("guests.profiles.read")),
    HOUSEKEEPING("governanca", "Governança", setOf("rooms.status.read")),
    MANAGEMENT(
        "gestao",
        "Gestão",
        setOf("iam.users.manage", "iam.roles.manage", "hotel.settings.manage", "rates.plans.manage"),
    ),
}

/** Áreas já construídas; a E06 acrescenta o mapa de cada uma. */
val IMPLEMENTED_AREAS: Set<Area> = emptySet()

/**
 * Áreas que o usuário pode ver, na ordem do enum, com `true` quando já estão implementadas.
 * Fora do modo de desenvolvimento só aparecem as implementadas; nele, as demais vêm com `false` ("em breve").
 */
fun visibleAreas(
    permissions: Set<String>,
    implemented: Set<Area>,
    devMode: Boolean,
): List<Pair<Area, Boolean>> =
    Area.entries
        .filter { area -> area.permissions.any { it in permissions } }
        .map { it to (it in implemented) }
        .filter { (_, isImplemented) -> isImplemented || devMode }
