package com.secrux.domain

import java.util.UUID

enum class TicketDraftItemType { FINDING, SCA_ISSUE }

data class TicketDraftItemRef(
    val type: TicketDraftItemType,
    val id: UUID
)

