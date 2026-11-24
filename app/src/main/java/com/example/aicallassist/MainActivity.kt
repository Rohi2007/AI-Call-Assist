package com.example.aicallassist

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.aicallassist.ui.theme.AICallAssistTheme
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*

data class DeviceContact(
    val id: String,
    val displayName: String,
    val phoneNumber: String?,
    val photoUri: String?,
    val photoThumbnail: Bitmap? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val manager = CallAssistManager(this)
        val contextManager = ContactContextManager(this)
        
        setContent {
            AICallAssistTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CallAssistMainScreen(
                        manager = manager,
                        contextManager = contextManager,
                        modifier = Modifier.padding(innerPadding),
                        onActivated = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
fun CallAssistMainScreen(
    manager: CallAssistManager, 
    contextManager: ContactContextManager,
    modifier: Modifier = Modifier,
    onActivated: () -> Unit
) {
    var isActive by remember { mutableStateOf(manager.isActive()) }
    var remainingTime by remember { mutableLongStateOf(0L) }
    var endTime by remember { mutableLongStateOf(manager.getEndTime()) }

    var currentTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(isActive, endTime) {
        while (isActive && endTime > System.currentTimeMillis()) {
            remainingTime = endTime - System.currentTimeMillis()
            delay(1000)
        }
        if (isActive && System.currentTimeMillis() >= endTime) {
            manager.deactivate()
            isActive = false
            remainingTime = 0
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = currentTab) {
            Tab(selected = currentTab == 0, onClick = { currentTab = 0 }, text = { Text("Status") })
            Tab(selected = currentTab == 1, onClick = { currentTab = 1 }, text = { Text("History") })
            Tab(selected = currentTab == 2, onClick = { currentTab = 2 }, text = { Text("VoIP") })
        }

        when (currentTab) {
            0 -> StatusTab(manager, contextManager, isActive, remainingTime, onActivated = {
                isActive = true
                endTime = manager.getEndTime()
                onActivated()
            }, onDeactivated = { isActive = false })
            1 -> HistoryTab(manager)
            2 -> VoipTab()
        }
    }
}

@Composable
fun StatusTab(
    manager: CallAssistManager,
    contextManager: ContactContextManager,
    isActive: Boolean,
    remainingTime: Long,
    onActivated: () -> Unit,
    onDeactivated: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var selectedContact by remember { mutableStateOf<DeviceContact?>(null) }
    var contactAiContext by remember { mutableStateOf("") }
    var isContextSaved by remember { mutableStateOf(false) }
    
    var selectedDuration by remember { mutableLongStateOf(manager.getSelectedDuration()) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customHours by remember { mutableStateOf("0") }
    var customMinutes by remember { mutableStateOf("30") }

    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    // Check if role is granted - authoritative source of truth
    var hasScreeningRole by remember { mutableStateOf(false) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    hasScreeningRole = roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
                } else {
                    hasScreeningRole = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AI Call Assist", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 1. CALL IDENTIFICATION ROLE STATUS (COMPACT)
        if (hasScreeningRole) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Call screening ready", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isActive) {
            ActiveStatusUI(manager, remainingTime, onDeactivate = {
                manager.deactivate()
                onDeactivated()
                Toast.makeText(context, "AI Call Assist turned OFF", Toast.LENGTH_SHORT).show()
            })
        } else {
            // ROLE SETUP CARD (Only if missing)
            if (!hasScreeningRole) {
                CallIdentificationStatus(false) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val roleManager = context.getSystemService(RoleManager::class.java)
                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                        roleLauncher.launch(intent)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 2. CONTACT SELECTION
            ContactSelectionSection(selectedContact, onSelectContact = { it ->
                selectedContact = it
                val saved = contextManager.getContext(it.phoneNumber ?: "")
                contactAiContext = saved?.specialContext ?: ""
                isContextSaved = saved != null
            })

            // 3. CONTEXT & ACTIVATION
            if (selectedContact != null) {
                Spacer(modifier = Modifier.height(24.dp))
                
                Text("AI Context", style = MaterialTheme.typography.labelLarge, color = Color.Gray, modifier = Modifier.align(Alignment.Start))
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = contactAiContext,
                    onValueChange = { 
                        contactAiContext = it
                        isContextSaved = false // Mark as unsaved on edit
                    },
                    placeholder = { Text("e.g. ${selectedContact!!.displayName} can discuss my courier delivery...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                
                if (isContextSaved) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Context saved", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                
                Button(
                    onClick = {
                        if (contactAiContext.isNotBlank()) {
                            contextManager.saveContext(ContactContext(
                                contactId = selectedContact!!.id,
                                phoneNumber = selectedContact!!.phoneNumber ?: "",
                                displayName = selectedContact!!.displayName,
                                specialContext = contactAiContext
                            ))
                            isContextSaved = true
                            Toast.makeText(context, "Context saved", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.End),
                    colors = if (isContextSaved) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors()
                ) {
                    Text(if (isContextSaved) "UPDATE CONTEXT" else "SAVE CONTEXT")
                }

                Spacer(modifier = Modifier.height(24.dp))

                ConfigurationUI(
                    selectedDuration = selectedDuration,
                    showCustomInput = showCustomInput,
                    customHours = customHours,
                    customMinutes = customMinutes,
                    onDurationSelected = { selectedDuration = it },
                    onCustomToggle = { showCustomInput = it },
                    onHoursChange = { customHours = it },
                    onMinutesChange = { customMinutes = it },
                    onActivate = {
                        val duration = if (showCustomInput) {
                            val h = customHours.toLongOrNull() ?: 0L
                            val m = customMinutes.toLongOrNull() ?: 0L
                            (h * 3600 + m * 60) * 1000L
                        } else selectedDuration

                        if (duration > 0 && isContextSaved) {
                            val missingPermissions = mutableListOf<String>()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                                missingPermissions.add(Manifest.permission.READ_PHONE_STATE)
                            }

                            if (missingPermissions.isNotEmpty()) {
                                permissionLauncher.launch(missingPermissions.toTypedArray())
                            } else {
                                manager.activate(duration, selectedContact!!.displayName, selectedContact!!.phoneNumber ?: "", contactAiContext)
                                onActivated()
                            }
                        } else if (!isContextSaved) {
                            Toast.makeText(context, "Please save the AI context first", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        DeveloperSection(manager)
    }
}

@Composable
fun CallIdentificationStatus(enabled: Boolean, onEnable: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (enabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (enabled) "Call identification enabled" else "Call identification not enabled",
                    fontWeight = FontWeight.Bold
                )
                if (!enabled) {
                    Text("Required to identify and silence calls correctly.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!enabled) {
                Button(onClick = onEnable, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("ENABLE", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ContactSelectionSection(
    selectedContact: DeviceContact?,
    onSelectContact: (DeviceContact) -> Unit
) {
    val contextLocal = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var allContacts by remember { mutableStateOf<List<DeviceContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) showPicker = true
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("AI CONTACT", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))
        
        if (selectedContact == null) {
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(contextLocal, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                        showPicker = true
                    } else {
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Person, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("SELECT CONTACT")
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(selectedContact.displayName, fontWeight = FontWeight.Bold)
                        Text(selectedContact.phoneNumber ?: "No number", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { showPicker = true }) { Text("CHANGE") }
                }
            }
        }
    }

    if (showPicker) {
        LaunchedEffect(Unit) {
            isLoading = true
            allContacts = fetchContacts(contextLocal)
            isLoading = false
        }
        
        ContactPickerDialog(
            contacts = allContacts,
            isLoading = isLoading,
            onDismiss = { showPicker = false },
            onSelect = {
                onSelectContact(it)
                showPicker = false
            }
        )
    }
}

@Composable
fun ContactPickerDialog(
    contacts: List<DeviceContact>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSelect: (DeviceContact) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(contacts, search) {
        if (search.isBlank()) contacts else contacts.filter { it.displayName.contains(search, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Contact") },
        text = {
            Column(modifier = Modifier.height(400.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search...") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    LazyColumn {
                        items(filtered) { contact ->
                            ListItem(
                                headlineContent = { Text(contact.displayName) },
                                supportingContent = { Text(contact.phoneNumber ?: "") },
                                modifier = Modifier.clickable { onSelect(contact) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

@Composable
fun ActiveStatusUI(manager: CallAssistManager, remainingTime: Long, onDeactivate: () -> Unit) {
    val contact = manager.getActiveContactInfo()
    val remainingMinutes = remainingTime / 60000

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("AI CALL ASSIST ACTIVE", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            
            if (contact != null) {
                Text("Selected contact:", style = MaterialTheme.typography.labelSmall)
                Text(contact.first, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("AI context:", style = MaterialTheme.typography.labelSmall)
                Text(contact.third, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            Text("Remaining: $remainingMinutes minutes", style = MaterialTheme.typography.titleLarge)
            
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDeactivate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("TURN OFF AI CALL ASSIST")
            }
        }
    }
}

@Composable
fun ConfigurationUI(
    selectedDuration: Long,
    showCustomInput: Boolean,
    customHours: String,
    customMinutes: String,
    onDurationSelected: (Long) -> Unit,
    onCustomToggle: (Boolean) -> Unit,
    onHoursChange: (String) -> Unit,
    onMinutesChange: (String) -> Unit,
    onActivate: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("How long should AI Call Assist handle calls?", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (!showCustomInput) {
            val durations = listOf(
                "30 Min" to 30 * 60 * 1000L,
                "1 Hour" to 60 * 60 * 1000L,
                "2 Hours" to 120 * 60 * 1000L,
                "3 Hours" to 180 * 60 * 1000L
            )
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.heightIn(max = 120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(durations) { (label, millis) ->
                    val isSelected = selectedDuration == millis
                    Button(onClick = { onDurationSelected(millis) }, colors = if (isSelected) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors()) {
                        Text(label)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { onCustomToggle(true) }, modifier = Modifier.fillMaxWidth()) { Text("Custom Duration") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = customHours, onValueChange = onHoursChange, label = { Text("Hrs") }, modifier = Modifier.width(70.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(":")
                OutlinedTextField(value = customMinutes, onValueChange = onMinutesChange, label = { Text("Min") }, modifier = Modifier.width(70.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            TextButton(onClick = { onCustomToggle(false) }) { Text("Back to presets") }
        }

        Spacer(modifier = Modifier.height(32.dp))
        Text(
            "AI Call Assist does not control your phone's sound mode. Set Silent or Do Not Disturb manually if needed.",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
            Text("ACTIVATE AI CALL ASSIST")
        }
    }
}

@Composable
fun DeveloperSection(manager: CallAssistManager) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.Start)) {
            Text(if (expanded) "Hide Developer Tools" else "Show Developer Tools")
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
        }
        
        AnimatedVisibility(visible = expanded) {
            Column {
                TelephonyStatusSection(manager)
                Spacer(modifier = Modifier.height(16.dp))
                TestEngineUI(manager)
            }
        }
    }
}

@Composable
fun TelephonyStatusSection(manager: CallAssistManager) {
    val info = manager.getLastCallerInfo()
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.DarkGray)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("REAL TELEPHONY STATUS (DEV)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Last Caller: ${info.first}", color = Color.LightGray, fontSize = 10.sp)
            Text("AI Active at call time: ${if (info.second) "YES" else "NO"}", color = Color.LightGray, fontSize = 10.sp)
        }
    }
}

@Composable
fun HistoryTab(manager: CallAssistManager) {
    var summaries by remember { mutableStateOf(manager.getSummaries()) }
    var showClearDialog by remember { mutableStateOf(false) }
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("While You Were Away", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (summaries.isNotEmpty()) {
                IconButton(onClick = { showClearDialog = true }) {
                    Icon(Icons.Default.Delete, "Clear History", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        if (summaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No call history yet.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(summaries) { summary ->
                    HistoryCard(summary, sdf.format(Date(summary.timestamp)))
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear call history?") },
            text = { Text("This will permanently remove your AI Call Assist interaction history.") },
            confirmButton = {
                Button(
                    onClick = {
                        manager.clearSummaries()
                        summaries = emptyList()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("CLEAR HISTORY")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
fun HistoryCard(summary: CallInteractionSummary, time: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(summary.callerName, fontWeight = FontWeight.Bold)
                    Text(time, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                PriorityBadge(summary.priority)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text("Reason: ${summary.reason}", style = MaterialTheme.typography.bodyMedium)
            Text("Action: ${summary.actionTaken}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            
            if (summary.sensitiveRequestDetected) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (summary.blockedSensitiveRequest) "⚠️ Blocked sensitive request" else "✅ Handled sensitive request",
                    color = if (summary.blockedSensitiveRequest) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PriorityBadge(priority: Priority) {
    val color = when (priority) {
        Priority.HIGH -> MaterialTheme.colorScheme.error
        Priority.MEDIUM -> Color(0xFFFF9800)
        Priority.LOW -> Color(0xFF4CAF50)
    }
    Surface(color = color.copy(alpha = 0.2f), shape = MaterialTheme.shapes.small) {
        Text(
            text = priority.name,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun TestEngineUI(manager: CallAssistManager) {
    val context = LocalContext.current
    val contextManager = remember { ContactContextManager(context) }
    val engine = remember { DecisionEngine() }
    
    var testCallerName by remember { mutableStateOf("Ravi") }
    var testPhoneNumber by remember { mutableStateOf("+91 98765 43210") }
    var testRequest by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<DecisionResult?>(null) }

    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("SIMULATED CALL TEST (DEV)", fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(value = testCallerName, onValueChange = { testCallerName = it }, label = { Text("Simulated Caller") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = testRequest, onValueChange = { testRequest = it }, label = { Text("Caller Request") }, placeholder = { Text("e.g. Can you give me the OTP for my parcel?") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                val contactCtx = contextManager.getContext(testPhoneNumber)?.specialContext
                val result = engine.evaluateRequest(testCallerName, testPhoneNumber, contactCtx, testRequest)
                testResult = result
                
                manager.addSummary(CallInteractionSummary(
                    callerName = testCallerName,
                    callerPhoneNumber = testPhoneNumber,
                    identifiedContact = contactCtx != null,
                    reason = testRequest,
                    priority = result.priority,
                    decision = result.decision,
                    actionTaken = result.reason,
                    sensitiveRequestDetected = result.matchedCategory == ActionCategory.SECURITY || result.matchedCategory == ActionCategory.FINANCIAL,
                    blockedSensitiveRequest = result.decision == Decision.BLOCK,
                    requiresUserAttention = result.decision == Decision.NEEDS_USER
                ))
            }, modifier = Modifier.align(Alignment.End)) {
                Text("EVALUATE & LOG")
            }

            if (testResult != null) {
                Spacer(modifier = Modifier.height(16.dp))
                val res = testResult!!
                val color = when (res.decision) {
                    Decision.ALLOW -> Color(0xFF4CAF50)
                    Decision.BLOCK -> Color(0xFFF44336)
                    Decision.NEEDS_USER -> Color(0xFFFF9800)
                }
                Column {
                    Text("Decision: ${res.decision}", color = color, fontWeight = FontWeight.Bold)
                    Text("Category: ${res.matchedCategory.displayName}")
                    Text("Match: ${res.contextMatch.name}")
                    Text("Priority: ${res.priority.name}")
                    Text("Reason: ${res.reason}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun VoipTab() {
    val context = LocalContext.current
    var roomId by remember { mutableStateOf("hackathon_demo") }
    var status by remember { mutableStateOf("Disconnected") }
    var incomingOffer by remember { mutableStateOf<SessionDescription?>(null) }
    var transcript by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentPartial by remember { mutableStateOf("") }
    
    val signalingManager = remember(roomId) { SignalingManager(roomId) }
    var webRtcManager by remember { mutableStateOf<WebRtcManager?>(null) }
    
    val sttManager = remember {
        SttManager(context, object : SttManager.SttListener {
            override fun onResult(text: String) {
                Log.d("VOSK_UI_DEBUG", "Local result: $text")
                // Fallback: Send local result to the other device via signaling
                signalingManager.sendSignal(SignalingManager.SignalingData(
                    type = "transcript",
                    text = text,
                    isFinal = true
                ))
                // Also show it locally for feedback
                transcript = transcript + "Self: $text"
                currentPartial = ""
            }
            override fun onPartialResult(text: String) {
                // Fallback: Send partial result
                signalingManager.sendSignal(SignalingManager.SignalingData(
                    type = "transcript",
                    text = text,
                    isFinal = false
                ))
                currentPartial = "Self: $text"
            }
            override fun onError(e: Exception) {
                Log.e("VOSK_UI_DEBUG", "STT Error: ${e.message}", e)
            }
        })
    }

    LaunchedEffect(status) {
        if (status == "CONNECTED") {
            Log.d("STT_DEBUG", "Call CONNECTED. Starting local mic STT fallback.")
            sttManager.startMicSTT()
        } else if (status == "Disconnected" || status == "FAILED") {
            sttManager.stopMicSTT()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) Toast.makeText(context, "Mic permission required for VoIP", Toast.LENGTH_SHORT).show()
    }

    val listener = object : WebRtcManager.WebRtcListener {
        override fun onIceCandidate(candidate: IceCandidate) {
            signalingManager.sendSignal(SignalingManager.SignalingData(
                type = "candidate",
                candidate = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex
            ))
        }

        override fun onLocalDescription(sdp: SessionDescription) {
            signalingManager.sendSignal(SignalingManager.SignalingData(
                type = sdp.type.canonicalForm(),
                sdp = sdp.description
            ))
        }

        override fun onConnectionStateChange(state: PeerConnection.PeerConnectionState) {
            status = state.name
        }

        override fun onRemoteAudio(audioData: ByteBuffer, sampleRate: Int, channels: Int) {
            sttManager.feedAudio(audioData, sampleRate, channels)
        }
    }

    DisposableEffect(signalingManager) {
        signalingManager.setListener(object : SignalingManager.SignalingListener {
            override fun onSignalReceived(signal: SignalingManager.SignalingData) {
                when (signal.type) {
                    "offer" -> {
                        incomingOffer = SessionDescription(SessionDescription.Type.OFFER, signal.sdp)
                        status = "Incoming Call..."
                    }
                    "answer" -> {
                        webRtcManager?.handleAnswer(SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))
                    }
                    "candidate" -> {
                        webRtcManager?.addIceCandidate(IceCandidate(signal.sdpMid, signal.sdpMLineIndex ?: 0, signal.candidate))
                    }
                    "transcript" -> {
                        if (signal.isFinal == true) {
                            transcript = transcript + "Caller: ${signal.text}"
                            currentPartial = ""
                        } else {
                            currentPartial = "Caller: ${signal.text}"
                        }
                    }
                }
            }
        })
        signalingManager.start()
        onDispose {
            webRtcManager?.endCall()
            signalingManager.stop()
            sttManager.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("AI Call Assist VoIP Demo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Module 8B: Remote STT Integration", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = roomId,
            onValueChange = { 
                roomId = it 
                status = "Disconnected"
            },
            label = { Text("Room ID (Share with other device)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        Text("Status: $status", fontWeight = FontWeight.Bold, color = if (status == "CONNECTED") Color(0xFF4CAF50) else Color.Gray)
        
        Spacer(modifier = Modifier.height(16.dp))

        // Transcript Area
        Card(
            modifier = Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                Text("LIVE TRANSCRIPT (REMOTE CALLER)", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                transcript.forEach { line ->
                    Text("Caller: \"$line\"", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                if (currentPartial.isNotBlank()) {
                    Text("Caller: \"$currentPartial...\"", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        if (status == "Disconnected" || status == "FAILED") {
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        val mgr = WebRtcManager(context, listener)
                        mgr.createPeerConnection()
                        mgr.createOffer()
                        webRtcManager = mgr
                        status = "Calling..."
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("START VOIP CALL (OFFER)")
            }
        }

        if (incomingOffer != null && status == "Incoming Call...") {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        val mgr = WebRtcManager(context, listener)
                        mgr.createPeerConnection()
                        mgr.handleOffer(incomingOffer!!)
                        webRtcManager = mgr
                        incomingOffer = null
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("ACCEPT / ANSWER CALL")
            }
        }

        if (status == "CONNECTED" || status == "Calling..." || status == "Incoming Call...") {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    webRtcManager?.endCall()
                    webRtcManager = null
                    status = "Disconnected"
                    incomingOffer = null
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("END CALL")
            }
        }
    }
}

suspend fun fetchContacts(context: Context): List<DeviceContact> = withContext(Dispatchers.IO) {
    val contacts = mutableListOf<DeviceContact>()
    val contentResolver = context.contentResolver
    val cursor = contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        ),
        null,
        null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )

    cursor?.use {
        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val photoIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

        if (idIndex == -1 || nameIndex == -1 || numberIndex == -1) return@use

        val processedIds = mutableSetOf<String>()

        while (it.moveToNext()) {
            val id = it.getString(idIndex)
            if (processedIds.contains(id)) continue
            processedIds.add(id)

            val name = it.getString(nameIndex) ?: "Unknown"
            val number = it.getString(numberIndex)
            val photoUri = if (photoIndex != -1) it.getString(photoIndex) else null
            
            var bitmap: Bitmap? = null
            if (photoUri != null) {
                try {
                    val inputStream: InputStream? = contentResolver.openInputStream(Uri.parse(photoUri))
                    bitmap = BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            contacts.add(DeviceContact(id, name, number, photoUri, bitmap))
        }
    }
    contacts
}
