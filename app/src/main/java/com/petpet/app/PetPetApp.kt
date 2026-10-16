package com.petpet.app

import android.app.Application
import com.google.android.gms.games.PlayGamesSdk

class PetPetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlayGamesSdk.initialize(this)
    }
}
