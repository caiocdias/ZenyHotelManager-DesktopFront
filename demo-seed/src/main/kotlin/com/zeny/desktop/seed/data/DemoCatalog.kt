package com.zeny.desktop.seed.data

import java.math.BigDecimal
import java.time.LocalDate

enum class HousekeepingTarget { DIRTY, CLEAN_PENDING_INSPECTION, CLEAN, INSPECTED }

enum class DemoRole(
    val template: String,
) {
    MANAGER("MANAGER"),
    RECEPTIONIST("RECEPTIONIST"),
    HOUSEKEEPER("HOUSEKEEPER"),
}

data class AddressSpec(
    val street: String,
    val number: String,
    val district: String,
    val city: String,
    val state: String,
    val postalCode: String,
    val ibgeCityCode: String,
)

data class HotelSpec(
    val tradeName: String,
    val legalName: String,
    val cnpj: String,
    val taxRegime: String,
    val timezone: String,
    val address: AddressSpec,
)

data class GuaranteePolicySpec(
    val name: String,
)

data class CancellationPolicySpec(
    val name: String,
    val deadlineHours: Int,
    val penaltyNights: Int,
)

data class RoomTypeSpec(
    val code: String,
    val name: String,
    val displayOrder: Int,
    val standardAdults: Int,
    val standardChildren: Int,
    val maxAdults: Int,
    val maxChildren: Int,
)

data class AreaSpec(
    val name: String,
    val kind: String,
)

data class RoomSpec(
    val number: String,
    val floor: Int,
    val roomTypeCode: String,
    val target: HousekeepingTarget,
    val outOfOrder: Boolean,
)

data class SeasonSpec(
    val name: String,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

data class RatePlanSpec(
    val code: String,
    val name: String,
    val mealPlan: String,
    val channels: List<String>,
    val roomTypeCodes: List<String>,
)

data class PriceSpec(
    val planCode: String,
    val roomTypeCode: String,
    val seasonName: String,
    val oneAdult: BigDecimal,
    val twoAdults: BigDecimal,
    val extraAdult: BigDecimal,
)

data class UserSpec(
    val role: DemoRole,
    val login: String,
    val name: String,
)

data class CompanySpec(
    val legalName: String,
    val tradeName: String,
    val cnpj: String,
)

data class GuestSpec(
    val key: String,
    val fullName: String,
    val birthDate: LocalDate,
    val cpf: String,
    val companyCnpj: String?,
)

data class ReservationSpec(
    val slot: String,
    val holderGuestKey: String,
    val roomTypeCode: String,
    val arrival: LocalDate,
    val departure: LocalDate,
    val adults: Int,
    val roomNumber: String?,
    val checkIn: Boolean,
) {
    val externalId get() = "DEMO-SEED-$slot"
}

data class DemoData(
    val hotel: HotelSpec,
    val guaranteePolicy: GuaranteePolicySpec,
    val cancellationPolicy: CancellationPolicySpec,
    val roomTypes: List<RoomTypeSpec>,
    val areas: List<AreaSpec>,
    val rooms: List<RoomSpec>,
    val seasons: List<SeasonSpec>,
    val ratePlan: RatePlanSpec,
    val prices: List<PriceSpec>,
    val users: List<UserSpec>,
    val companies: List<CompanySpec>,
    val guests: List<GuestSpec>,
    val reservations: List<ReservationSpec>,
)

/** Catálogo fictício e determinístico da demo: mesma data operacional, mesmos dados. */
@Suppress("MagicNumber") // catálogo é dado literal da demo: quantidades, tarifas e datas são o conteúdo
object DemoCatalog {
    const val HOTEL_TRADE_NAME = "Pousada Zeny Demonstração"

    private const val PLAN_CODE = "BALCAO"
    private const val LOW_SEASON = "Baixa (demo)"
    private const val HIGH_SEASON = "Alta (demo)"
    private const val LOW_DAYS = 29L
    private const val HIGH_START = 30L
    private const val HIGH_END = 59L

    fun build(businessDate: LocalDate): DemoData {
        val companies = companies()
        return DemoData(
            hotel = hotel(),
            guaranteePolicy = GuaranteePolicySpec("Sem garantia (demo)"),
            cancellationPolicy = CancellationPolicySpec("Flexível (demo)", deadlineHours = 24, penaltyNights = 1),
            roomTypes =
                listOf(
                    RoomTypeSpec("STD", "Standard", 1, 2, 0, 3, 1),
                    RoomTypeSpec("LUX", "Luxo", 2, 2, 0, 3, 2),
                    RoomTypeSpec("SUI", "Suíte", 3, 2, 0, 4, 2),
                ),
            areas =
                listOf(
                    AreaSpec("Recepção", "LOBBY"),
                    AreaSpec("Piscina", "POOL"),
                    AreaSpec("Restaurante", "OTHER"),
                ),
            rooms = rooms(),
            seasons =
                listOf(
                    SeasonSpec(LOW_SEASON, businessDate, businessDate.plusDays(LOW_DAYS)),
                    SeasonSpec(HIGH_SEASON, businessDate.plusDays(HIGH_START), businessDate.plusDays(HIGH_END)),
                ),
            ratePlan =
                RatePlanSpec(
                    code = PLAN_CODE,
                    name = "Tarifa balcão",
                    mealPlan = "BREAKFAST",
                    channels = listOf("DIRECT", "WEBSITE"),
                    roomTypeCodes = listOf("STD", "LUX", "SUI"),
                ),
            prices = prices(),
            users =
                listOf(
                    UserSpec(DemoRole.MANAGER, "gerente", "Gerente da Demo"),
                    UserSpec(DemoRole.RECEPTIONIST, "recepcao", "Recepção da Demo"),
                    UserSpec(DemoRole.HOUSEKEEPER, "governanta", "Governanta da Demo"),
                ),
            companies = companies,
            guests = guests(companies),
            reservations = reservations(businessDate),
        )
    }

    private fun hotel() =
        HotelSpec(
            tradeName = HOTEL_TRADE_NAME,
            legalName = "Zeny Demonstração Hotelaria Ltda",
            cnpj = FictitiousDocuments.cnpj("445500010001"),
            taxRegime = "SIMPLES_NACIONAL",
            timezone = "America/Sao_Paulo",
            address =
                AddressSpec(
                    street = "Rua Fictícia da Demonstração",
                    number = "100",
                    district = "Centro",
                    city = "São Paulo",
                    state = "SP",
                    postalCode = "01000-000",
                    ibgeCityCode = "3550308",
                ),
        )

    private fun rooms(): List<RoomSpec> {
        val types =
            mapOf(
                "101" to "STD",
                "102" to "STD",
                "103" to "STD",
                "104" to "STD",
                "105" to "STD",
                "106" to "STD",
                "107" to "STD",
                "108" to "STD",
                "109" to "LUX",
                "110" to "LUX",
                "201" to "STD",
                "202" to "STD",
                "203" to "STD",
                "204" to "STD",
                "205" to "LUX",
                "206" to "LUX",
                "207" to "LUX",
                "208" to "LUX",
                "209" to "SUI",
                "210" to "SUI",
            )
        val dirty = setOf("106", "107", "108")
        val pending = setOf("109", "110")
        val clean = setOf("201", "202")
        return types.map { (number, type) ->
            val target =
                when (number) {
                    in dirty, "210" -> HousekeepingTarget.DIRTY
                    in pending -> HousekeepingTarget.CLEAN_PENDING_INSPECTION
                    in clean -> HousekeepingTarget.CLEAN
                    else -> HousekeepingTarget.INSPECTED
                }
            RoomSpec(number, number.take(1).toInt(), type, target, outOfOrder = number == "210")
        }
    }

    private fun prices(): List<PriceSpec> {
        fun price(
            type: String,
            season: String,
            one: String,
            two: String,
            extra: String,
        ) = PriceSpec(PLAN_CODE, type, season, BigDecimal(one), BigDecimal(two), BigDecimal(extra))
        return listOf(
            price("STD", LOW_SEASON, "280.00", "320.00", "90.00"),
            price("LUX", LOW_SEASON, "420.00", "480.00", "110.00"),
            price("SUI", LOW_SEASON, "650.00", "720.00", "150.00"),
            price("STD", HIGH_SEASON, "340.00", "390.00", "100.00"),
            price("LUX", HIGH_SEASON, "500.00", "570.00", "120.00"),
            price("SUI", HIGH_SEASON, "780.00", "860.00", "170.00"),
        )
    }

    private fun companies() =
        listOf(
            CompanySpec(
                "Empresa Fictícia Alfa Demonstração Ltda",
                "Alfa Demonstração",
                FictitiousDocuments.cnpj("445500020001"),
            ),
            CompanySpec(
                "Empresa Fictícia Beta Demonstração Ltda",
                "Beta Demonstração",
                FictitiousDocuments.cnpj("445500030001"),
            ),
        )

    private fun guests(companies: List<CompanySpec>): List<GuestSpec> {
        val names =
            listOf(
                "Um",
                "Dois",
                "Três",
                "Quatro",
                "Cinco",
                "Seis",
                "Sete",
                "Oito",
                "Nove",
                "Dez",
                "Onze",
                "Doze",
            )
        return names.mapIndexed { i, name ->
            val n = i + 1
            GuestSpec(
                key = "G$n",
                fullName = "Hóspede Demonstração $name",
                birthDate = LocalDate.of(1980 + i, (i % 12) + 1, (i % 27) + 1),
                cpf = FictitiousDocuments.cpf("2026001${n.toString().padStart(2, '0')}"),
                companyCnpj =
                    when (n) {
                        1, 2, 3 -> companies[0].cnpj
                        4, 5 -> companies[1].cnpj
                        else -> null
                    },
            )
        }
    }

    private fun reservations(d: LocalDate): List<ReservationSpec> {
        fun r(
            slot: String,
            guest: Int,
            type: String,
            nights: Long,
            adults: Int,
            room: String?,
            checkIn: Boolean,
        ) = ReservationSpec(slot, "G$guest", type, d, d.plusDays(nights), adults, room, checkIn)
        return listOf(
            r("A1", 1, "STD", 2, 2, "101", false),
            r("A2", 2, "STD", 3, 1, "102", false),
            r("A3", 3, "LUX", 2, 2, null, false),
            r("A4", 4, "STD", 3, 2, null, false),
            r("H1", 5, "STD", 1, 2, "103", true),
            r("H2", 6, "STD", 2, 1, "104", true),
            r("H3", 7, "LUX", 3, 2, "205", true),
            r("H4", 8, "LUX", 4, 3, "206", true),
            r("H5", 9, "SUI", 2, 4, "209", true),
            r("S1", 10, "STD", 0, 1, "105", true),
            r("S2", 11, "LUX", 0, 2, "207", true),
        )
    }
}
