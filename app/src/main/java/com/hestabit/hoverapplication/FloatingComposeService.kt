package com.hestabit.hoverapplication

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import android.widget.FrameLayout
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

class FloatingComposeService : Service(), ViewModelStoreOwner, SavedStateRegistryOwner, SensorEventListener {

    private lateinit var windowManager: WindowManager
    private lateinit var composeView: ComposeView
    private lateinit var layoutParams: WindowManager.LayoutParams

    // Sensor components
    private lateinit var sensorManager: SensorManager
    private var gravitySensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    // Screen dimensions
    private var absoluteScreenWidth = 0
    private var absoluteScreenHeight = 0

    // Physics properties
    private var isPhysicsEnabled = false
    private var velocityX = 0f
    private var velocityY = 0f
    private var gravityX = 0f
    private var gravityY = 800f
    private val gravityStrength = 600f
    private val friction = 0.98f
    private val bounceDamping = 0.6f

    // Lifecycle components
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val viewModelStore = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        getAbsoluteScreenDimensions()
        setupSensors()
        setupSelectiveTouchWindow()
        startForegroundService()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun getAbsoluteScreenDimensions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                absoluteScreenWidth = metrics.bounds.width()
                absoluteScreenHeight = metrics.bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val realSize = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(realSize)
                absoluteScreenWidth = realSize.x
                absoluteScreenHeight = realSize.y
            }

            if (absoluteScreenWidth <= 0 || absoluteScreenHeight <= 0) {
                val displayMetrics = resources.displayMetrics
                absoluteScreenWidth = displayMetrics.widthPixels
                absoluteScreenHeight = displayMetrics.heightPixels
            }

        } catch (e: Exception) {
            e.printStackTrace()
            val displayMetrics = resources.displayMetrics
            absoluteScreenWidth = displayMetrics.widthPixels
            absoluteScreenHeight = displayMetrics.heightPixels
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        if (gravitySensor == null) {
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
    }

    private fun startSensorListening() {
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: accelerometerSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopSensorListening() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                    gravityX = -it.values[0] * gravityStrength
                    gravityY = it.values[1] * gravityStrength

                    if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                        val alpha = 0.8f
                        gravityX = alpha * gravityX + (1 - alpha) * (-it.values[0] * gravityStrength)
                        gravityY = alpha * gravityY + (1 - alpha) * (it.values[1] * gravityStrength)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun setupSelectiveTouchWindow() {
        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // CRITICAL: Use WRAP_CONTENT and position dynamically instead of full screen
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            // Key flags: NOT_FOCUSABLE allows background touches, no full-screen flags
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = absoluteScreenWidth / 2
            y = absoluteScreenHeight / 2

            // Optional: Display cutout support if needed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Create a sized container for the floating element only
        composeView = ComposeView(this).apply {
            // Set lifecycle owners
            setViewTreeLifecycleOwner(this@FloatingComposeService)
            setViewTreeSavedStateRegistryOwner(this@FloatingComposeService)
            setViewTreeViewModelStoreOwner(this@FloatingComposeService)

            setContent {
                MaterialTheme {
                    SelectiveTouchFloatingContent { closeService() }
                }
            }
        }

        windowManager.addView(composeView, layoutParams)
    }

    @Composable
    private fun SelectiveTouchFloatingContent(onClose: () -> Unit) {
        var isExpanded by remember { mutableStateOf(false) }
        var isDragging by remember { mutableStateOf(false) }
        var lastDragTime by remember { mutableStateOf(0L) }
        var lastDragX by remember { mutableStateOf(0f) }
        var lastDragY by remember { mutableStateOf(0f) }

        // Auto-enable physics
        LaunchedEffect(isDragging) {
            if (!isDragging && !isPhysicsEnabled) {
                delay(100)
                isPhysicsEnabled = true
                startSensorListening()
            }
        }

        // Physics simulation that updates window position
        LaunchedEffect(isPhysicsEnabled) {
            if (isPhysicsEnabled) {
                val deltaTime = 16f / 1000f

                while (isPhysicsEnabled && !isDragging) {
                    velocityX += gravityX * deltaTime
                    velocityY += gravityY * deltaTime

                    velocityX *= friction
                    velocityY *= friction

                    val newX = layoutParams.x + (velocityX * deltaTime).toInt()
                    val newY = layoutParams.y + (velocityY * deltaTime).toInt()

                    // Handle collisions and update window position
                    handleWindowCollisions(newX, newY)

                    try {
                        windowManager.updateViewLayout(composeView, layoutParams)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    if (abs(velocityX) < 20f && abs(velocityY) < 20f) {
                        velocityX *= 0.9f
                        velocityY *= 0.9f
                    }

                    delay(16)
                }
            }
        }

        // Only the floating element - no full screen overlay
        Box(
            modifier = Modifier
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            isPhysicsEnabled = false
                            stopSensorListening()
                            velocityX = 0f
                            velocityY = 0f
                            lastDragTime = System.currentTimeMillis()
                            lastDragX = layoutParams.x.toFloat()
                            lastDragY = layoutParams.y.toFloat()
                        },
                        onDragEnd = {
                            isDragging = false

                            val currentTime = System.currentTimeMillis()
                            val timeDelta = (currentTime - lastDragTime) / 1000f

                            if (timeDelta > 0) {
                                velocityX = (layoutParams.x - lastDragX) / timeDelta
                                velocityY = (layoutParams.y - lastDragY) / timeDelta

                                velocityX = velocityX.coerceIn(-1500f, 1500f)
                                velocityY = velocityY.coerceIn(-1500f, 1500f)
                            }

                            isPhysicsEnabled = true
                            startSensorListening()
                        }
                    ) { change, dragAmount ->
                        change.consume()

                        val newX = layoutParams.x + dragAmount.x.toInt()
                        val newY = layoutParams.y + dragAmount.y.toInt()

                        // Update window position during drag
                        layoutParams.x = constrainX(newX)
                        layoutParams.y = constrainY(newY)

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDragTime > 50) {
                            lastDragX = layoutParams.x.toFloat()
                            lastDragY = layoutParams.y.toFloat()
                            lastDragTime = currentTime
                        }

                        try {
                            windowManager.updateViewLayout(composeView, layoutParams)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
        ) {
            if (isExpanded) {
                SelectiveExpandedWindow(
                    onMinimize = { isExpanded = false },
                    onClose = onClose,
                    onResetPhysics = {
                        velocityX = 0f
                        velocityY = 0f
                        isPhysicsEnabled = true
                        startSensorListening()
                    },
                    isDragging = isDragging,
                    isPhysicsEnabled = isPhysicsEnabled,
                    gravityX = gravityX,
                    gravityY = gravityY,
                    screenInfo = "Screen: ${absoluteScreenWidth}x${absoluteScreenHeight}",
                    position = "Pos: ${layoutParams.x},${layoutParams.y}"
                )
            } else {
                SelectiveMinimizedButton(
                    onClick = { isExpanded = true },
                    onLongClick = {
                        velocityX += (Math.random() * 400 - 200).toFloat()
                        velocityY += (Math.random() * 400 - 200).toFloat()
                    },
                    isDragging = isDragging,
                    isPhysicsEnabled = isPhysicsEnabled
                )
            }
        }
    }

    private fun handleWindowCollisions(newX: Int, newY: Int) {
        val windowWidth = if (composeView.width > 0) composeView.width else 200
        val windowHeight = if (composeView.height > 0) composeView.height else 200

        when {
            newX <= 0 -> {
                layoutParams.x = 0
                velocityX = abs(velocityX) * bounceDamping
            }
            newX >= absoluteScreenWidth - windowWidth -> {
                layoutParams.x = absoluteScreenWidth - windowWidth
                velocityX = -abs(velocityX) * bounceDamping
            }
            else -> {
                layoutParams.x = newX
            }
        }

        when {
            newY <= 0 -> {
                layoutParams.y = 0
                velocityY = abs(velocityY) * bounceDamping
            }
            newY >= absoluteScreenHeight - windowHeight -> {
                layoutParams.y = absoluteScreenHeight - windowHeight
                velocityY = -abs(velocityY) * bounceDamping
                velocityX *= 0.8f
            }
            else -> {
                layoutParams.y = newY
            }
        }
    }

    private fun constrainX(newX: Int): Int {
        val windowWidth = if (composeView.width > 0) composeView.width else 200
        return newX.coerceIn(0, absoluteScreenWidth - windowWidth)
    }

    private fun constrainY(newY: Int): Int {
        val windowHeight = if (composeView.height > 0) composeView.height else 200
        return newY.coerceIn(0, absoluteScreenHeight - windowHeight)
    }

    @Composable
    private fun SelectiveExpandedWindow(
        onMinimize: () -> Unit,
        onClose: () -> Unit,
        onResetPhysics: () -> Unit,
        isDragging: Boolean,
        isPhysicsEnabled: Boolean,
        gravityX: Float,
        gravityY: Float,
        screenInfo: String,
        position: String
    ) {
        Surface(
            modifier = Modifier
                .width(200.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = if (isDragging) 12.dp else 8.dp
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Gravity indicator
                Surface(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .rotate(atan2(gravityX, gravityY) * 180f / PI.toFloat()),
                    shape = RoundedCornerShape(2.dp),
                    color = when {
                        isDragging -> Color.Red
                        isPhysicsEnabled -> Color.Green
                        else -> Color.Gray.copy(alpha = 0.5f)
                    }
                ) {}

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Background OK",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.Black
                    )

                    IconButton(
                        onClick = onMinimize,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Minimize",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Text(
                    text = when {
                        isDragging -> "Background touches work!"
                        isPhysicsEnabled -> "Gravity + Background!"
                        else -> "Physics disabled"
                    },
                    fontSize = 9.sp,
                    color = when {
                        isDragging -> Color.Red
                        isPhysicsEnabled -> Color.Green
                        else -> Color.Gray
                    },
                    textAlign = TextAlign.Center
                )

                Text(
                    text = screenInfo,
                    fontSize = 7.sp,
                    color = Color.Blue,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = position,
                    fontSize = 7.sp,
                    color = Color.Magenta,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onResetPhysics,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reset", fontSize = 8.sp)
                    }

                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Close", fontSize = 8.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun SelectiveMinimizedButton(
        onClick: () -> Unit,
        onLongClick: () -> Unit,
        isDragging: Boolean,
        isPhysicsEnabled: Boolean
    ) {
        var isPressed by remember { mutableStateOf(false) }

        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier
                .size(56.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onLongPress = { onLongClick() }
                    )
                },
            containerColor = when {
                isDragging -> Color.Red
                isPhysicsEnabled -> Color(0xFF4CAF50) // Green
                else -> MaterialTheme.colorScheme.primary
            },
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = if (isDragging) 12.dp else 6.dp
            ),
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "Background Touch Enabled",
                tint = Color.White
            )
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, "floating_service_channel")
            .setContentTitle("Background Touch Enabled")
            .setContentText("Floating window with background interaction")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun closeService() {
        stopSensorListening()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSensorListening()
        isPhysicsEnabled = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        try {
            windowManager.removeView(composeView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}









