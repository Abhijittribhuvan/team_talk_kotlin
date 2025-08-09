package com.example.team_talk_kotlin.data.model

import java.io.Serializable

data class Guard(
    val id: String,
    val name: String,
    val companyId: String
) : Serializable