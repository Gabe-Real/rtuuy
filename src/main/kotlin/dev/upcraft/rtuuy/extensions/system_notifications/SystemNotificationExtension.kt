package dev.upcraft.rtuuy.extensions.system_notifications

import dev.kord.common.entity.Snowflake
import dev.kord.core.entity.Webhook
import dev.kord.core.event.gateway.ReadyEvent
import dev.kord.rest.builder.message.embed
import dev.kordex.core.extensions.Extension
import dev.kordex.core.extensions.event
import dev.kordex.core.utils.envOfOrNull
import dev.kordex.core.utils.envOrNull
import dev.kordex.core.utils.executeStored
import dev.upcraft.rtuuy.App
import dev.upcraft.rtuuy.i18n.Translations
import dev.upcraft.rtuuy.util.ntfy.NtfyClient
import dev.upcraft.rtuuy.util.ntfy.NtfyTags
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import java.net.URL

class SystemNotificationExtension : Extension() {
	override val name = "system_notifications"

	private val discordWebhookUrls = envOrNull("SYSTEM_NOTIFICATION_WEBHOOK_URLS")
	private var discordWebhooks: MutableSet<Webhook> = mutableSetOf()

	private val ntfyTopic = envOrNull("SYSTEM_NOTIFICATION_NTFY_TOPIC")
	private var ntfyClient: NtfyClient? = ntfyTopic?.let {
		NtfyClient {
			client = HttpClient {
				install(ContentNegotiation) {
				}
			}
			accessToken = envOrNull("SYSTEM_NOTIFICATION_NTFY_TOKEN")
			// custom URL if provided, public service otherwise
			server = envOfOrNull<URL>("SYSTEM_NOTIFICATION_NTFY_URL")
		}
	}

	override suspend fun setup() {
		discordWebhookUrls?.let { urls ->
			urls.split(",").forEach { url ->
				val split = url.split("/")

				if (split.size != 7) {
					throw IllegalArgumentException("Invalid SYSTEM_NOTIFICATION_WEBHOOK_URL: $url")
				}

				val webhookId = Snowflake(split[5])
				val token = split[6]

				val webhook = kord.getWebhookWithToken(webhookId, token)
				discordWebhooks.add(webhook)
			}
		}

		var seen = false
		event<ReadyEvent> {
			if (seen) {
				return@event
			}
			seen = true

			val applicationId = kord.getApplicationInfo().id
			val botUsername = kord.getSelf().username

			discordWebhooks.forEach { webhook ->
				webhook.executeStored {
					embed {
						title = Translations.SystemNotifications.StartupWebhook.title
							.translateNamed(
								"version" to App.VERSION,
								"bot_username" to botUsername,
								"application_id" to applicationId,
							)
						description = Translations.SystemNotifications.StartupWebhook.message
							.translateNamed(
								"version" to App.VERSION,
								"bot_username" to botUsername,
								"application_id" to applicationId,
							)
					}
				}
			}

			ntfyClient?.let { ntfy ->
				ntfy.publish(ntfyTopic!!) {
					tags.add(NtfyTags.Robot)
					message = Translations.SystemNotifications.StartupNtfy.message
						.translateNamed(
							"version" to App.VERSION,
							"bot_username" to botUsername,
							"application_id" to applicationId
						)
				}
			}
		}
	}
}
