/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.app

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mokkery.mock
import org.junit.After
import org.junit.runner.RunWith
import org.koin.android.ext.android.get
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import org.meshtastic.core.service.MeshLogCleanup
import org.meshtastic.core.service.worker.MeshLogCleanupWorker
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = DeferredWorkManagerApplication::class, sdk = [34])
class DeferredWorkManagerStartupTest {
    @After
    fun tearDown() {
        resetWorkManagerForStartupTest()
    }

    @Test
    fun applicationDefersWorkManagerAndRetainsKoinWorkerFactory() {
        val application = ApplicationProvider.getApplicationContext<MeshUtilApplication>()
        assertFalse(WorkManager.isInitialized())
        assertIs<KoinWorkerFactory>(application.workManagerConfiguration.workerFactory)

        val executor = Executors.newSingleThreadExecutor()
        val workManager =
            try {
                executor.submit<WorkManager> { WorkManager.getInstance(application) }.get()
            } finally {
                executor.shutdown()
            }

        assertTrue(WorkManager.isInitialized())
        assertSame(workManager, application.get<WorkManager>())
        loadKoinModules(module { single { MeshLogCleanup(mock(), mock()) } })
        val worker =
            TestListenableWorkerBuilder<MeshLogCleanupWorker>(application)
                .setWorkerFactory(application.workManagerConfiguration.workerFactory)
                .build()
        assertIs<MeshLogCleanupWorker>(worker)
    }
}

internal class DeferredWorkManagerApplication : MeshUtilApplication() {
    override fun onCreate() {
        resetWorkManagerForStartupTest()
        super.onCreate()
    }

    override fun startBackgroundInit() = Unit

    override fun loadBundledSqlite() = Unit

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder(super.workManagerConfiguration)
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build()
}

/** Robolectric reuses AndroidX statics; closing WorkDatabase alone does not model a fresh process. */
private fun resetWorkManagerForStartupTest() {
    WorkManagerTestInitHelper.closeWorkDatabase()
    ReflectionHelpers.setStaticField(WorkManagerImpl::class.java, "sDelegatedInstance", null)
    ReflectionHelpers.setStaticField(WorkManagerImpl::class.java, "sDefaultInstance", null)
}
