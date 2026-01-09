package com.example.uts

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.uts.ui.theme.PengingatMinumTheme
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// --- VIEW MODEL (Logika Bisnis & Auth) ---
class MainViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // State User Login
    private val _currentUser = MutableStateFlow(auth.currentUser)
    val currentUser = _currentUser.asStateFlow()

    // State Data Minum
    private val _uiState = MutableStateFlow(DrinkData())
    val uiState: StateFlow<DrinkData> = _uiState.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // Cek login status saat aplikasi dibuka
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
            if (firebaseAuth.currentUser != null) {
                loadData() // Load data hanya jika user login
            } else {
                _uiState.value = DrinkData() // Reset UI jika logout
            }
        }
    }

    // --- FUNGSI AUTH ---
    fun login(email: String, pass: String, context: Context, onSuccess: () -> Unit) {
        _isLoading.value = true
        auth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener {
                _isLoading.value = false
                onSuccess()
            }
            .addOnFailureListener {
                _isLoading.value = false
                Toast.makeText(context, "Login Gagal: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    fun register(email: String, pass: String, context: Context, onSuccess: () -> Unit) {
        _isLoading.value = true
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnSuccessListener {
                _isLoading.value = false
                Toast.makeText(context, "Akun Berhasil Dibuat!", Toast.LENGTH_SHORT).show()
                onSuccess()
            }
            .addOnFailureListener {
                _isLoading.value = false
                Toast.makeText(context, "Register Gagal: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    fun logout() {
        auth.signOut()
    }

    // --- FUNGSI DATA ---
    private fun loadData() {
        val uid = auth.currentUser?.uid ?: return
        val docRef = db.collection("daily_intake").document(uid)

        docRef.addSnapshotListener { snapshot, e ->
            if (e != null) return@addSnapshotListener
            if (snapshot != null && snapshot.exists()) {
                val data = snapshot.toObject(DrinkData::class.java)
                if (data != null && isSameDay(data.lastUpdated)) {
                    _uiState.value = data
                } else {
                    resetData(keepTarget = true, oldTarget = data?.targetMl ?: 2500)
                }
            } else {
                saveData(DrinkData()) // Buat data baru untuk user baru
            }
        }
    }

    fun addDrink(amount: Int) {
        val current = _uiState.value
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val newData = current.copy(
            currentMl = current.currentMl + amount,
            riwayat = current.riwayat + "$amount|$time",
            lastUpdated = Timestamp.now()
        )
        saveData(newData)
    }

    fun undoLastDrink() {
        val current = _uiState.value
        if (current.riwayat.isNotEmpty()) {
            val lastEntry = current.riwayat.last()
            val amountToRemove = lastEntry.split("|")[0].toIntOrNull() ?: 0
            val newData = current.copy(
                currentMl = (current.currentMl - amountToRemove).coerceAtLeast(0),
                riwayat = current.riwayat.dropLast(1),
                lastUpdated = Timestamp.now()
            )
            saveData(newData)
        }
    }

    fun updateTarget(newTarget: Int) {
        saveData(_uiState.value.copy(targetMl = newTarget))
    }

    fun resetData(keepTarget: Boolean = false, oldTarget: Int = 2500) {
        val target = if (keepTarget) oldTarget else 2500
        saveData(DrinkData(currentMl = 0, targetMl = target, riwayat = emptyList(), lastUpdated = Timestamp.now()))
    }

    private fun saveData(data: DrinkData) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("daily_intake").document(uid).set(data, SetOptions.merge())
    }

    private fun isSameDay(timestamp: Timestamp): Boolean {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(timestamp.toDate()) == sdf.format(Date())
    }
}

// --- NAVIGASI ---
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Login : Screen("login", "Masuk", Icons.Default.Login)
    object Register : Screen("register", "Daftar", Icons.Default.PersonAdd)
    object Home : Screen("home", "Utama", Icons.Default.Home)
    object Stats : Screen("stats", "Statistik", Icons.Default.BarChart)
    object Settings : Screen("settings", "Pengaturan", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PengingatMinumTheme {
                MainNavigation(viewModel)
            }
        }
    }
}

@Composable
fun MainNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()

    // Bottom Bar Items (Hanya muncul jika sudah login)
    val bottomItems = listOf(Screen.Home, Screen.Stats, Screen.Settings)

    Scaffold(
        bottomBar = {
            if (currentUser != null) {
                NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    bottomItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentRoute == screen.route,
                            colors = NavigationBarItemDefaults.colors(selectedTextColor = Color(0xFF2193b0)),
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.Home.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            // Jika user null (belum login) ke Login, jika tidak ke Home
            startDestination = if (currentUser == null) Screen.Login.route else Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Login.route) { LoginScreen(navController, viewModel) }
            composable(Screen.Register.route) { RegisterScreen(navController, viewModel) }
            composable(Screen.Home.route) { PengingatMinumScreen(viewModel) }
            composable(Screen.Stats.route) { StatistikScreen(viewModel) }
            composable(Screen.Settings.route) { PengaturanScreen(viewModel) }
        }
    }
}

// --- HALAMAN LOGIN ---
@Composable
fun LoginScreen(navController: androidx.navigation.NavController, viewModel: MainViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2193b0), Color(0xFF6dd5ed)))), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.padding(32.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Selamat Datang Kembali", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2193b0))
                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(24.dp))

                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = { viewModel.login(email, password, context) { navController.navigate(Screen.Home.route) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2193b0))
                    ) { Text("Masuk") }

                    TextButton(onClick = { navController.navigate(Screen.Register.route) }) {
                        Text("Belum punya akun? Daftar")
                    }
                }
            }
        }
    }
}

// --- HALAMAN REGISTER ---
@Composable
fun RegisterScreen(navController: androidx.navigation.NavController, viewModel: MainViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFB993D6), Color(0xFF8CA6DB)))), contentAlignment = Alignment.Center) {
        Card(modifier = Modifier.padding(32.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Buat Akun Baru", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8CA6DB))
                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(24.dp))

                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Button(
                        onClick = { viewModel.register(email, password, context) { navController.navigate(Screen.Home.route) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8CA6DB))
                    ) { Text("Daftar Sekarang") }

                    TextButton(onClick = { navController.popBackStack() }) {
                        Text("Sudah punya akun? Masuk")
                    }
                }
            }
        }
    }
}

// --- HALAMAN UTAMA (HOME) ---
@Composable
fun PengingatMinumScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showEditDialog by remember { mutableStateOf(false) }

    val progress = if (uiState.targetMl > 0) uiState.currentMl.toFloat() / uiState.targetMl.toFloat() else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), animationSpec = tween(1000), label = "progress")

    Column(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2193b0), Color(0xFF6dd5ed)))).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WaterDrop, null, tint = Color.White, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Hari Ini", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Progress Card
        Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { 1f }, modifier = Modifier.size(150.dp), color = Color.LightGray.copy(alpha = 0.2f), strokeWidth = 12.dp)
                    CircularProgressIndicator(progress = { animatedProgress }, modifier = Modifier.size(150.dp), color = Color(0xFF2193b0), strokeWidth = 12.dp, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
                    Text("${(animatedProgress * 100).toInt()}%", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2193b0))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { showEditDialog = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${uiState.currentMl} / ${uiState.targetMl} ml", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.DarkGray)
                    Icon(Icons.Default.Edit, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DrinkButton(amount = 100, label = "Small") { viewModel.addDrink(100) }
            DrinkButton(amount = 250, label = "Medium") { viewModel.addDrink(250) }
            DrinkButton(amount = 500, label = "Large") { viewModel.addDrink(500) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = { viewModel.undoLastDrink() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))) {
            Icon(Icons.Default.Undo, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(8.dp)); Text("Batalkan Terakhir")
        }
    }
    if (showEditDialog) EditTargetDialog(uiState.targetMl, { showEditDialog = false }, { viewModel.updateTarget(it); showEditDialog = false })
}

// --- HALAMAN STATISTIK ---
@Composable
fun StatistikScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)).padding(24.dp)) {
        Text("Statistik", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2193b0))
        Spacer(modifier = Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Total Hari Ini", fontWeight = FontWeight.Bold)
                Text("${uiState.currentMl} ml", fontSize = 32.sp, color = Color(0xFF2193b0), fontWeight = FontWeight.Bold)
                Divider(modifier = Modifier.padding(vertical = 12.dp))
                Text("Frekuensi: ${uiState.riwayat.size} kali minum")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.riwayat.reversed()) { log ->
                val parts = log.split("|")
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(parts.getOrElse(0) { "0" } + " ml", fontWeight = FontWeight.Bold)
                        Text(parts.getOrElse(1) { "--:--" }, color = Color.Gray)
                    }
                }
            }
        }
    }
}

// --- HALAMAN PENGATURAN (BERFUNGSI) ---
@Composable
fun PengaturanScreen(viewModel: MainViewModel) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Simpan status notifikasi di SharedPreferences (Setting Lokal)
    val sharedPref = context.getSharedPreferences("Settings", Context.MODE_PRIVATE)
    var notifEnabled by remember { mutableStateOf(sharedPref.getBoolean("notif_enabled", true)) }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5)).padding(24.dp)) {
        Text("Pengaturan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2193b0))
        Spacer(modifier = Modifier.height(24.dp))

        // Profil - Data Asli
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, modifier = Modifier.size(48.dp), tint = Color(0xFF2193b0))
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Akun", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    // Tampilkan Email yang sedang login
                    Text(currentUser?.email ?: "Guest", color = Color.Gray)
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))

        // Opsi
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Aktifkan Notifikasi")
                    Switch(checked = notifEnabled, onCheckedChange = { isChecked ->
                        notifEnabled = isChecked
                        // Simpan setting agar tidak hilang saat aplikasi ditutup
                        sharedPref.edit().putBoolean("notif_enabled", isChecked).apply()
                        val msg = if(isChecked) "Notifikasi Diaktifkan" else "Notifikasi Dimatikan"
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    })
                }
                Divider()
                Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.resetData() }.padding(16.dp)) {
                    Text("Reset Data Hari Ini", color = Color(0xFFEF5350))
                }
                Divider()
                // Tombol LOGOUT Berfungsi
                Row(modifier = Modifier.fillMaxWidth().clickable { viewModel.logout() }.padding(16.dp)) {
                    Icon(Icons.Default.ExitToApp, null, tint = Color.Red)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Keluar (Logout)", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- HELPER COMPONENTS ---
@Composable
fun DrinkButton(amount: Int, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onClick, modifier = Modifier.size(70.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = Color.White), elevation = ButtonDefaults.buttonElevation(4.dp)) {
            Text("+$amount", color = Color(0xFF2193b0), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun EditTargetDialog(currentTarget: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(currentTarget.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target (ml)") },
        text = { OutlinedTextField(value = text, onValueChange = { if (it.all { char -> char.isDigit() }) text = it }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(text.toIntOrNull() ?: currentTarget) }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}