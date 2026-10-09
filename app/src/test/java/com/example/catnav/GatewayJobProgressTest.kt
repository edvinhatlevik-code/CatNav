package com.example.catnav

import com.example.catnav.data.GatewayJob
import com.example.catnav.ui.jobProgressText
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayJobProgressTest {
    @Test
    fun queuedJobUsesGatewayQueueDescription() {
        val job = GatewayJob(
            jobId = "job-1",
            trackerId = 1L,
            command = "FETCH",
            status = "QUEUED",
            detail = null,
            createdAtMs = 0L
        )

        assertEquals("Queued at gateway", jobProgressText(job))
    }

    @Test
    fun legacyReceiveWindowDetailUsesCurrentWakeStatus() {
        val job = GatewayJob(
            jobId = "job-2",
            trackerId = 1L,
            command = "WAKE",
            status = "IN_PROGRESS",
            detail = "Waiting for the tracker receive window",
            createdAtMs = 0L
        )

        assertEquals("Gateway is attempting to wake the tracker", jobProgressText(job))
    }
}
