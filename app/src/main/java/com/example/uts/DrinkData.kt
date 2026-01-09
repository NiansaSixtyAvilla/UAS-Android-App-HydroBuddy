package com.example.uts

import com.google.firebase.Timestamp

data class DrinkData(
    val currentMl: Int = 0,        // Total minum dalam ml
    val targetMl: Int = 2500,      // Target default 2.5 Liter
    val lastUpdated: Timestamp = Timestamp.now(),
    // Kita simpan history dengan format: "250|10:30" (Jumlah|Jam) agar mudah di-undo
    val riwayat: List<String> = emptyList()
)