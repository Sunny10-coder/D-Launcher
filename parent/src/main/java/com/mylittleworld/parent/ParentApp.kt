package com.mylittleworld.parent

import android.app.Application

class ParentApp : Application() {

    lateinit var container: ParentContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = ParentContainer(this)
    }
}
