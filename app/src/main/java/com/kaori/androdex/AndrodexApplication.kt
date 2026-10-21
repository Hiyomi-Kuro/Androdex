package com.kaori.androdex

import android.app.Application
import com.kaori.androdex.data.AppGraph
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class AndrodexApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        graph = AppGraph(applicationContext)
    }
}
