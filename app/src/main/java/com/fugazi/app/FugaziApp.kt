package com.fugazi.app

import android.app.Application
import com.fugazi.app.ai.Reflector
import com.fugazi.app.journal.CheckIns
import com.fugazi.app.journal.JournalStore
import com.fugazi.app.journal.ThoughtStore
import com.fugazi.app.sense.SignalStore

class FugaziApp : Application() {
    override fun onCreate() {
        super.onCreate()
        JournalStore.init(this)
        SignalStore.init(this)
        ThoughtStore.init(this)
        Reflector.init(this)
        CheckIns.ensureChannel(this)
        CheckIns.schedule(this)
    }
}
