package dev.upcraft.rtuuy.extensions.ban_sync

import dev.kord.common.entity.Snowflake
import dev.kord.core.behavior.ban
import dev.kord.core.event.Event
import dev.kord.core.event.guild.BanAddEvent
import dev.kord.core.event.guild.BanRemoveEvent
import dev.kordex.core.checks.failed
import dev.kordex.core.checks.guildFor
import dev.kordex.core.checks.passed
import dev.kordex.core.checks.types.CheckContext
import dev.kordex.core.checks.userFor
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import dev.kordex.core.storage.StorageType
import dev.kordex.core.storage.StorageUnit
import dev.kordex.core.utils.env
import dev.kordex.core.utils.scheduling.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class BanSyncExtension : Extension() {
	override val name = "ban_sync"

	private val SYNCED_BAN_SERVERS = env("SYNCED_BAN_SERVERS")
	private val DRY_RUN = env("DRY_RUN")
	private var syncedServers = mutableListOf<Snowflake>()
	private var syncingBans = mutableListOf<Snowflake>()
	private val scheduler = Scheduler()

	private val storageUnit = StorageUnit<BanSyncConfig>(
		StorageType.Config,
		"rtuuy",
		"ban_sync"
	)

	suspend fun <T : Event> CheckContext<T>.isSyncedGuild() {
		if (!passed) {
			return
		}

		val logger = KotlinLogging.logger("dev.upcraft.rtuuy.BanSyncExtension.isSyncedGuild")

		if (guildFor(event)?.id in syncedServers) {
			logger.passed()
			pass()
			return
		}

		logger.failed("Guild is not in the list of guilds with synced bans!")
		fail("Guild is not in the list of guilds with synced bans!")
	}

	suspend fun <T : Event> CheckContext<T>.isNotSyncing() {
		if (!passed) {
			return
		}

		val logger = KotlinLogging.logger("dev.upcraft.rtuuy.BanSyncExtension.isNotSyncing")

		if (userFor(event)?.id in syncingBans) {
			logger.failed("This user's ban is currently being synced! Avoiding redundancy.")
			fail("This user's ban is currently being synced! Avoiding redundancy.")
			return
		}

		logger.passed()
		pass()
	}

	override suspend fun setup() {
		if (SYNCED_BAN_SERVERS.isNotEmpty()) {
			val logger = KotlinLogging.logger("dev.upcraft.rtuuy.BanSyncExtension.initialSync")
			syncedServers = SYNCED_BAN_SERVERS.split(',').map { Snowflake(it) }.toMutableList()
			logger.passed("Successfully loaded servers from SYNCED_BAN_SERVERS!")
		}

		if (DRY_RUN.lowercase() == "true") {
			val logger = KotlinLogging.logger("dev.upcraft.rtuuy.BanSyncExtension.dryRunEnabled")
			logger.passed("Dry run has been enabled. All bans to be synced will not be applied!")
		}

		val syncInterval = getConfig().syncInterval

		scheduler.schedule(syncInterval, true, "Ban Sync", 1, true) {
			syncBans()
		}

		event<BanAddEvent> {
			check {
				isSyncedGuild()
				isNotSyncing()
			}

			action {
				val logger = KotlinLogging.logger("dev.upcraft.rtuuy.BanSyncExtension.banSyncOnBanAdd")
				val guildName = event.guild.asGuildOrNull()?.name
				logger.info {
					"User ${event.user.username} (${event.user.id}) has been banned on $guildName (${event.guildId})! Syncing that ban between synced servers."
				}

				syncingBans.add(event.user.id)
				val banReason = event.getBan().reason
				val syncedServers = syncedServers
				for (syncedServer in syncedServers) {
					if (syncedServer != event.guildId) {
						val guild = kord.getGuildOrNull(syncedServer)

						if (guild == null) {
							logger.failed("Synced server with ID $syncedServer has failed to sync! Removing it from the synced server list.")
							syncedServers.remove(syncedServer)
							saveConfig()
						} else {
							if (guild.getBanOrNull(event.user.id) == null) {
								guild.ban(event.user.id) {
									reason = "Synced from $guildName (${event.guild.id}). Reason: $banReason"
								}
								logger.passed("Successfully synced the ban to ${guild.name} (${guild.id})")
							} else {
								logger.failed("User has already been banned on ${guild.name} (${guild.id})")
							}
						}
					}
				}
				syncingBans.remove(event.user.id)
			}
		}

		event<BanRemoveEvent> {
			check {
				isSyncedGuild()
				isNotSyncing()
			}

			action {
				val logger = KotlinLogging.logger("dev.upcraft.rtuuy.BanSyncExtension.banSyncOnBanRemove")
				val guildName = event.guild.asGuildOrNull()?.name
				logger.info {
					"User ${event.user.username} (${event.user.id}) has been unbanned on $guildName (${event.guildId})! Syncing that unban between synced servers."
				}

				syncingBans.add(event.user.id)
				val syncedServers = syncedServers
				for (syncedServer in syncedServers) {
					if (syncedServer != event.guildId) {
						val guild = kord.getGuildOrNull(syncedServer)

						if (guild == null) {
							logger.failed("Synced server with ID $syncedServer has failed to sync! Removing it from the synced server list.")
							syncedServers.remove(syncedServer)
							saveConfig()
						} else {
							if (guild.getBanOrNull(event.user.id) != null) {
								guild.unban(event.user.id, "Synced from $guildName (${event.guild.id}).")
							}
						}
					}
				}
				syncingBans.remove(event.user.id)
			}
		}
	}

	private suspend fun syncBans() {
		val config = getConfig()
		val now = Clock.System.now()
		val logger = KotlinLogging.logger("dev.upcraft.rtuuy.BanSyncExtension.syncBans")

		// Just in case
		if (config.lastSynced < now) {
			val guildToBan = mutableMapOf<Snowflake, MutableMap<Snowflake, String?>>()

			for (syncedServer in syncedServers) {
				val guild = kord.getGuildOrNull(syncedServer)

				if (guild == null) {
					logger.failed("Synced server with ID $syncedServer has failed to sync! Removing it from the synced server list.")
					syncedServers.remove(syncedServer)
				} else {
					guild.bans.collect {
						if (!guildToBan.containsKey(it.userId)) {
							guildToBan.put(it.userId, mutableMapOf<Snowflake, String?>(guild.id to it.reason))
						} else {
							val existingMap = guildToBan[it.userId]
							if (existingMap != null) {
								existingMap.put(guild.id, it.reason)
								guildToBan.put(it.userId, existingMap)
							}
						}
					}
				}
			}

			for (syncedServer in syncedServers) {
				val guild = kord.getGuildOrNull(syncedServer)

				if (guild == null) {
					logger.failed("Synced server with ID $syncedServer has failed to sync! Removing it from the synced server list.")
					syncedServers.remove(syncedServer)
				} else {
					for (entry in guildToBan) {
						syncingBans.add(entry.key)
						val guildName = kord.getGuildOrNull(guild.id)?.name ?: "[Guild Unknown]"
						if (entry.value.containsKey(guild.id)) {
							if (DRY_RUN.lowercase() == "true") {
								logger.info { "Dry-Run: Ignoring redundant ban for ${entry.key} on $guildName (${guild.id})" }
							}
						} else {
							val username = guild.getMemberOrNull(entry.key)?.username ?: "[Username Unknown]"
							val newReason = entry.value.map {
								"${it.value} (${bot.kordRef.getGuildOrNull(it.key)?.name ?: it.key})"
							}.joinToString(", ")
							if (DRY_RUN.lowercase() == "true") {
								logger.info { "Dry-Run on $guildName (${guild.id})): Banned $username (${entry.key}) for: $newReason" }
							} else {
								logger.info { "$guildName (${guild.id})): Banned $username (${entry.key}) for: $newReason" }
								guild.ban(entry.key) {
									logger.info { "$guildName (${guild.id})): Banned $username (${entry.key}) for: $newReason" }
									reason = "Synced ban. Reasons: $newReason"
								}
							}
						}
						syncingBans.remove(entry.key)
					}
				}
			}

			config.lastSynced = now
			saveConfig()
		}
	}

	private suspend fun getConfig(): BanSyncConfig {
		var config = storageUnit.get()

		if (config == null) {
			config = BanSyncConfig(2L.hours, Instant.DISTANT_PAST)

			storageUnit.save(config)
		}

		return config
	}

	private suspend fun saveConfig() {
		storageUnit.save()
	}

	data class BanEntry(
		val guildId: MutableSet<Snowflake>,
		val reason: MutableList<String?>
	)
}
