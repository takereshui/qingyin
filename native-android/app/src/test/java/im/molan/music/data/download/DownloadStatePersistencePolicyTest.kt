package im.molan.music.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatePersistencePolicyTest {
    @Test
    fun firstProgressUpdateIsPersistedImmediately() {
        val policy = DownloadStatePersistencePolicy(minimumProgressPersistIntervalMs = 2_000L)

        assertTrue(policy.shouldPersistProgress(0L))
    }

    @Test
    fun progressUpdatesInsideIntervalAreNotPersistedAgain() {
        val policy = DownloadStatePersistencePolicy(minimumProgressPersistIntervalMs = 2_000L)

        policy.shouldPersistProgress(100L)

        assertFalse(policy.shouldPersistProgress(2_099L))
    }

    @Test
    fun progressUpdateAtIntervalBoundaryIsPersisted() {
        val policy = DownloadStatePersistencePolicy(minimumProgressPersistIntervalMs = 2_000L)

        policy.shouldPersistProgress(100L)

        assertTrue(policy.shouldPersistProgress(2_100L))
    }
}
