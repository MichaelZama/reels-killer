package app.bloccareels.service

import app.bloccareels.block.Counters
import app.bloccareels.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Counters saved in the repository; the returned value is available to the block screen at once. */
internal class RepoCounters(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
) : Counters {
    private var date: String? = null
    private var blocks = 0

    override fun onBlockShown(): Int {
        val today = repo.todayString()
        val s = repo.state.value
        val persisted = if (s.counterDate == today) s.blocksToday else 0
        if (date != today) {
            date = today
            blocks = 0
        }
        blocks = maxOf(blocks, persisted) + 1
        scope.launch { repo.increment(SettingsRepository.Counter.BLOCKS) }
        return blocks
    }

    override fun onContinue() {
        scope.launch { repo.increment(SettingsRepository.Counter.CONTINUE) }
    }

    override fun onExit() {
        scope.launch { repo.increment(SettingsRepository.Counter.EXIT) }
    }
}
