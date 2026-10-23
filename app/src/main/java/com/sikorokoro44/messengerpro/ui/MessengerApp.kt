package com.sikorokoro44.messengerpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sikorokoro44.messengerpro.R
import com.sikorokoro44.messengerpro.engine.FixtureStatus
import com.sikorokoro44.messengerpro.engine.RegistryData
import com.sikorokoro44.messengerpro.engine.SettingsEngine
import com.sikorokoro44.messengerpro.model.Account
import com.sikorokoro44.messengerpro.model.ActivityEvent
import com.sikorokoro44.messengerpro.model.Message

private enum class Tab(val label: String) {
    CHATS("Chats"),
    CONTACTS("Contacts"),
    ACTIVITY("Activity"),
    SETTINGS("Settings")
}

@Composable
fun MessengerApp(controller: MessengerController) {
    val theme = controller.theme()
    MessengerProTheme(theme) {
        val version = controller.version
        val account = if (version >= 0) controller.account() else null
        if (account == null) {
            AuthScreen(controller)
        } else {
            MainShell(controller, account)
        }
    }
}

@Composable
private fun AuthScreen(controller: MessengerController) {
    val version = controller.version
    var creating by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(text = stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; error = null },
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        if (creating) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it; error = null },
                label = { Text(stringResource(R.string.display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; error = null },
            label = { Text("Server URL (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Leave blank for offline mode. A server enables real cross-device messaging.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                error = if (serverUrl.isBlank()) {
                    if (creating) controller.register(username.trim(), displayName.trim(), password)
                    else controller.login(username.trim(), password)
                } else {
                    controller.connectServer(serverUrl.trim(), creating, username.trim(), displayName.trim(), password)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (creating) R.string.register else R.string.sign_in))
        }
        TextButton(onClick = { creating = !creating; error = null }) {
            Text(if (creating) "I already have an account" else "Create a new account")
        }
        error?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "${RegistryData.fixtures.size} tracked features",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (version < 0) Text("")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(controller: MessengerController, account: Account) {
    var tab by remember { mutableStateOf(Tab.CHATS) }
    var openChat by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(account.id) {
        while (true) {
            if (controller.isRemote()) withContext(Dispatchers.IO) { controller.pollRemote() }
            delay(4000)
        }
    }

    val open = openChat
    if (open != null) {
        ChatScreen(controller, account, open) { openChat = null }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    Text(
                        text = account.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.values().forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.CHATS -> ChatsScreen(controller, account) { openChat = it }
                Tab.CONTACTS -> ContactsScreen(controller, account) { openChat = it }
                Tab.ACTIVITY -> ActivityScreen(controller, account)
                Tab.SETTINGS -> SettingsScreen(controller, account)
            }
        }
    }
}

private fun nameOf(controller: MessengerController, ownerId: String, accountId: String): String =
    controller.repo.contacts.get(ownerId, accountId)?.nickname
        ?: controller.repo.auth.findById(accountId)?.displayName
        ?: accountId

@Composable
private fun ChatsScreen(controller: MessengerController, account: Account, onOpen: (String) -> Unit) {
    val version = controller.version
    var query by remember { mutableStateOf("") }

    val contacts = remember(version, account.id) {
        controller.repo.contacts.list(account.id).map { it.contactAccountId }
    }
    val filtered = remember(version, query, contacts) {
        contacts.filter { nameOf(controller, account.id, it).lowercase().contains(query.lowercase()) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.no_conversations),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered) { contactId ->
                    val messages = controller.repo.messaging.messages(account.id, contactId)
                    val last = messages.lastOrNull()
                    val conversation = controller.repo.messaging.conversation(account.id, contactId)
                    ListItem(
                        headlineContent = { Text(nameOf(controller, account.id, contactId)) },
                        supportingContent = {
                            Text(
                                when {
                                    last == null -> "Start a conversation"
                                    last.deleted -> "Message deleted"
                                    else -> last.body
                                },
                                maxLines = 1
                            )
                        },
                        trailingContent = {
                            if (conversation.unread > 0) {
                                Text("${conversation.unread}", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                        modifier = Modifier.clickable { onOpen(contactId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    controller: MessengerController,
    account: Account,
    otherId: String,
    onBack: () -> Unit
) {
    val version = controller.version
    var text by remember { mutableStateOf("") }
    LaunchedEffect(otherId) { controller.markRead(otherId) }
    val messages = remember(version, otherId) { controller.repo.messaging.messages(account.id, otherId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(nameOf(controller, account.id, otherId)) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        controller.send(otherId, text)
                        text = ""
                    },
                    enabled = text.isNotBlank()
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No messages yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { message -> MessageBubble(message, message.senderId == account.id) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, mine: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = if (message.deleted) "This message was deleted" else message.body,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row {
                    if (message.editedAt != null) {
                        Text("edited · ", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(message.state.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                    if (message.reactions.isNotEmpty()) {
                        Text(
                            " · " + message.reactions.joinToString(" ") { it.emoji },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactsScreen(controller: MessengerController, account: Account, onOpen: (String) -> Unit) {
    val version = controller.version
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val contacts = remember(version, account.id) { controller.repo.contacts.list(account.id, includeHidden = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; error = null },
                label = { Text(stringResource(R.string.add_contact)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                error = controller.addContact(username.trim())
                if (error == null) username = ""
            }) { Text("Add") }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }
        if (contacts.isEmpty()) {
            Text(
                text = stringResource(R.string.no_contacts),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts, key = { it.id }) { contact ->
                    ListItem(
                        headlineContent = {
                            Text(contact.nickname ?: nameOf(controller, account.id, contact.contactAccountId))
                        },
                        supportingContent = { Text(contact.contactAccountId) },
                        trailingContent = {
                            Row {
                                TextButton(onClick = { controller.toggleFavourite(contact.contactAccountId) }) {
                                    Text(if (contact.favourite) "★" else "☆")
                                }
                                TextButton(onClick = { controller.toggleBlock(contact.contactAccountId) }) {
                                    Text(if (contact.blocked) stringResource(R.string.unblock) else stringResource(R.string.block))
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(contact.contactAccountId) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ActivityScreen(controller: MessengerController, account: Account) {
    val version = controller.version
    val events = remember(version, account.id) { controller.repo.activity.all(account.id) }

    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No activity yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(events, key = { it.id }) { event -> ActivityRow(event) }
    }
}

@Composable
private fun ActivityRow(event: ActivityEvent) {
    ListItem(
        headlineContent = { Text(event.type.replace('_', ' ')) },
        supportingContent = { Text(event.detail) },
        trailingContent = { Text(event.at.toString().takeLast(8)) }
    )
    HorizontalDivider()
}

@Composable
private fun SettingsScreen(controller: MessengerController, account: Account) {
    val version = controller.version
    val scope = rememberCoroutineScope()
    val settings = remember(version) { controller.repo.settingsEngine.settings() }
    val counts = remember(version) { controller.fixtureCounts() }
    val categories = remember(version) { controller.categoryCounts() }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            val server = controller.serverUrl()
            Text(if (server == null) "Offline mode - local data only" else server, style = MaterialTheme.typography.bodySmall)
            controller.remoteStatus?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { scope.launch(Dispatchers.IO) { controller.refreshRemote() } },
                    enabled = server != null
                ) { Text("Sync now") }
                if (server != null) {
                    Button(onClick = { controller.disconnectServer() }) { Text("Disconnect") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsEngine.Theme.values().forEach { theme ->
                    Button(
                        onClick = { controller.setTheme(theme) },
                        enabled = settings.theme != theme
                    ) { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Privacy and notifications", style = MaterialTheme.typography.titleMedium)
        }
        item { SettingRow("Read receipts", settings.readReceipts) { controller.toggleSetting("receipts") } }
        item { SettingRow("Last seen visible", settings.lastSeenVisible) { controller.toggleSetting("lastSeen") } }
        item { SettingRow("Online status visible", settings.onlineVisible) { controller.toggleSetting("online") } }
        item { SettingRow("Message previews", settings.messagePreviews) { controller.toggleSetting("previews") } }
        item { SettingRow("Notifications", settings.notificationsEnabled) { controller.toggleSetting("notifications") } }
        item { SettingRow("On-device AI helpers", settings.aiEnabled) { controller.toggleSetting("ai") } }
        item {
            Spacer(Modifier.height(16.dp))
            Text("Fixture status (${RegistryData.fixtures.size} tracked)", style = MaterialTheme.typography.titleMedium)
            counts.forEach { (status, count) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(status.name.replace('_', ' '), modifier = Modifier.weight(1f))
                    Text("$count")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("By category", style = MaterialTheme.typography.labelLarge)
            categories.forEach { (name, count) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                    Text(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("$count", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("Signed in as ${account.username}", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { controller.logout() }) { Text(stringResource(R.string.logout)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
