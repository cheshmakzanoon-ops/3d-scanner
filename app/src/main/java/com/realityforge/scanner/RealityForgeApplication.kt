package com.realityforge.scanner

import android.app.Application
import androidx.room.Room
import com.realityforge.scanner.data.ProjectRepository
import com.realityforge.scanner.data.ScanDatabase

class RealityForgeApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, ScanDatabase::class.java, "realityforge.db").build() }
    val repository by lazy { ProjectRepository(this, database.dao()) }
}
