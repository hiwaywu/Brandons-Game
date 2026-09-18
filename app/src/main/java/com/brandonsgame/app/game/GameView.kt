package com.brandonsgame.app.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(
    context: Context,
    private val onExitToMenu: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    companion object {
        private const val MATCH_SECONDS = 180f
        private const val PLAYER_BASE_SPEED = 280f
        private const val STEAL_AMOUNT = 3
        private const val STEAL_EFFECT_SECONDS = 10f
        private const val INVULN_SECONDS = 1.4f
        private const val ATTACK_COOLDOWN = 0.85f
        private const val CANNON_FIRE_INTERVAL = 10f
        private const val MAP_SCALE = 2.6f
        private const val CAMERA_LERP = 8f
    }

    private enum class AttackType(
        val label: String,
        val color: Int,
        val needsCloseRange: Boolean,
        val range: Float
    ) {
        THROW("Throw", Color.rgb(255, 180, 80), true, 110f),
        PUNCH("Punch", Color.rgb(255, 90, 90), true, 95f),
        SHOOT("Shoot", Color.rgb(120, 200, 255), false, 320f),
        SNOWBALL("Snowball", Color.rgb(200, 240, 255), false, 260f),
        KICK("Kick", Color.rgb(255, 140, 60), true, 100f),
        LASER("Laser", Color.rgb(255, 60, 180), false, 380f)
    }

    private enum class DragonKind {
        BLUE_SPEED,
        GOLD_MAGNET,
        GREEN_SHIELD,
        PURPLE_SLOW_TIME,
        RED_RAGE
    }

    private data class Actor(
        var x: Float,
        var y: Float,
        var radius: Float,
        var coins: Int = 0,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var speed: Float = PLAYER_BASE_SPEED,
        var lives: Int = 3,
        var invuln: Float = 0f,
        var isPlayer: Boolean = false,
        var color: Int = Color.WHITE,
        var stealTimer: Float = 0f,
        var shieldTimer: Float = 0f,
        var magnetTimer: Float = 0f,
        var rageTimer: Float = 0f,
        var slowTimer: Float = 0f,
        var aiThink: Float = 0f,
        var targetX: Float = 0f,
        var targetY: Float = 0f
    )

    private data class Coin(var x: Float, var y: Float, var radius: Float = 16f, var spin: Float = 0f)
    private data class Fireball(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var radius: Float = 22f
    )
    private data class Cannon(
        var x: Float,
        var y: Float,
        var angle: Float = 0f,
        var cooldown: Float
    )
    private data class Dragon(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var kind: DragonKind,
        var radius: Float = 34f,
        var pulse: Float = 0f
    )
    private data class AttackFx(
        var x: Float,
        var y: Float,
        var type: AttackType,
        var life: Float = 0.45f,
        var hit: Boolean = false
    )
    private data class FloatingText(
        var x: Float,
        var y: Float,
        var text: String,
        var color: Int,
        var life: Float = 1.1f
    )

    private val holderRef = holder
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false

    private var screenW = 1f
    private var screenH = 1f
    private var mapW = 1f
    private var mapH = 1f
    private var camX = 0f
    private var camY = 0f
    private var timeLeft = MATCH_SECONDS
    private var gameOver = false
    private var wonByTimer = false
    private var attackCooldown = 0f
    private var lastAttackLabel = ""
    private var spawnCoinTimer = 0f
    private var spawnDragonTimer = 0f
    private var messageBanner = "Grab coins! Dodge fireballs!"
    private var bannerTimer = 3f

    private lateinit var player: Actor
    private val rivals = mutableListOf<Actor>()
    private val coins = mutableListOf<Coin>()
    private val fireballs = mutableListOf<Fireball>()
    private val cannons = mutableListOf<Cannon>()
    private val dragons = mutableListOf<Dragon>()
    private val attackFx = mutableListOf<AttackFx>()
    private val floatTexts = mutableListOf<FloatingText>()

    private var joyActive = false
    private var joyId = -1
    private var joyCx = 0f
    private var joyCy = 0f
    private var joyKnobX = 0f
    private var joyKnobY = 0f
    private var moveX = 0f
    private var moveY = 0f

    private val attackBtn = RectF()
    private val menuBtn = RectF()
    private val againBtn = RectF()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    init {
        holderRef.addCallback(this)
        isFocusable = true
    }

    fun pause() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun resume() {
        if (running) return
        running = true
        thread = Thread(this, "CoinGrabLoop").also { it.start() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        mapW = screenW * MAP_SCALE
        mapH = screenH * MAP_SCALE
        layoutHud()
        if (!::player.isInitialized) {
            startMatch()
        } else {
            snapCameraToPlayer()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        pause()
    }

    private fun layoutHud() {
        val pad = screenW * 0.05f
        joyCx = pad + 110f
        joyCy = screenH - pad - 110f
        joyKnobX = joyCx
        joyKnobY = joyCy
        val btnR = 72f
        attackBtn.set(
            screenW - pad - btnR * 2f,
            screenH - pad - btnR * 2f,
            screenW - pad,
            screenH - pad
        )
        menuBtn.set(screenW * 0.15f, screenH * 0.62f, screenW * 0.85f, screenH * 0.70f)
        againBtn.set(screenW * 0.15f, screenH * 0.72f, screenW * 0.85f, screenH * 0.80f)
    }

    private fun snapCameraToPlayer() {
        camX = (player.x - screenW * 0.5f).coerceIn(0f, (mapW - screenW).coerceAtLeast(0f))
        camY = (player.y - screenH * 0.5f).coerceIn(0f, (mapH - screenH).coerceAtLeast(0f))
    }

    private fun updateCamera(dt: Float) {
        val targetX = player.x - screenW * 0.5f
        val targetY = player.y - screenH * 0.5f
        val maxX = (mapW - screenW).coerceAtLeast(0f)
        val maxY = (mapH - screenH).coerceAtLeast(0f)
        val follow = (CAMERA_LERP * dt).coerceIn(0f, 1f)
        camX += (targetX.coerceIn(0f, maxX) - camX) * follow
        camY += (targetY.coerceIn(0f, maxY) - camY) * follow
    }

    private fun startMatch() {
        timeLeft = MATCH_SECONDS
        gameOver = false
        wonByTimer = false
        attackCooldown = 0f
        lastAttackLabel = ""
        spawnCoinTimer = 0f
        spawnDragonTimer = 2f
        messageBanner = "Minigame No.1 — Coin Grab!"
        bannerTimer = 3f
        coins.clear()
        fireballs.clear()
        cannons.clear()
        dragons.clear()
        attackFx.clear()
        floatTexts.clear()
        rivals.clear()

        player = Actor(
            x = mapW * 0.5f,
            y = mapH * 0.5f,
            radius = 28f,
            isPlayer = true,
            color = Color.rgb(80, 200, 120),
            speed = PLAYER_BASE_SPEED,
            lives = 3,
            coins = 0
        )

        val rivalColors = intArrayOf(
            Color.rgb(255, 110, 110),
            Color.rgb(255, 180, 70),
            Color.rgb(170, 120, 255)
        )
        for (i in 0 until 3) {
            rivals += Actor(
                x = Random.nextFloat() * (mapW - 100f) + 50f,
                y = Random.nextFloat() * (mapH - 160f) + 80f,
                radius = 26f,
                color = rivalColors[i],
                speed = PLAYER_BASE_SPEED * 0.85f,
                coins = Random.nextInt(2, 8),
                targetX = mapW * 0.5f,
                targetY = mapH * 0.5f
            )
        }

        repeat(28) { spawnCoin() }
        setupCannons()
        spawnDragon(DragonKind.BLUE_SPEED)
        spawnDragon(randomAbilityDragon())
        moveX = 0f
        moveY = 0f
        joyActive = false
        snapCameraToPlayer()
    }

    private fun randomAbilityDragon(): DragonKind {
        val options = listOf(
            DragonKind.GOLD_MAGNET,
            DragonKind.GREEN_SHIELD,
            DragonKind.PURPLE_SLOW_TIME,
            DragonKind.RED_RAGE
        )
        return options.random()
    }

    private fun spawnCoin() {
        coins += Coin(
            x = Random.nextFloat() * (mapW - 60f) + 30f,
            y = Random.nextFloat() * (mapH - 100f) + 50f
        )
    }

    private fun setupCannons() {
        val side = 34f
        val upperY = 150f
        val midY = mapH * 0.5f
        val bottomY = mapH - 180f
        cannons += Cannon(side, upperY, cooldown = 1f)
        cannons += Cannon(mapW - side, upperY, cooldown = 2.7f)
        cannons += Cannon(side, midY, cooldown = 4.3f)
        cannons += Cannon(mapW - side, midY, cooldown = 6f)
        cannons += Cannon(side, bottomY, cooldown = 7.7f)
        cannons += Cannon(mapW - side, bottomY, cooldown = 9.3f)
    }

    private fun fireCannon(cannon: Cannon) {
        val spread = (Random.nextFloat() - 0.5f) * 0.16f
        val shotAngle = cannon.angle + spread
        val speed = Random.nextFloat() * 80f + 240f
        val muzzleDistance = 48f
        fireballs += Fireball(
            x = cannon.x + cos(shotAngle) * muzzleDistance,
            y = cannon.y + sin(shotAngle) * muzzleDistance,
            vx = cos(shotAngle) * speed,
            vy = sin(shotAngle) * speed
        )
    }

    private fun spawnDragon(kind: DragonKind) {
        val speedMul = if (kind == DragonKind.BLUE_SPEED) 2f else 1.2f
        val angle = Random.nextFloat() * Math.PI.toFloat() * 2f
        val spd = PLAYER_BASE_SPEED * speedMul
        dragons += Dragon(
            x = Random.nextFloat() * (mapW - 80f) + 40f,
            y = Random.nextFloat() * (mapH - 120f) + 60f,
            vx = cos(angle) * spd,
            vy = sin(angle) * spd,
            kind = kind
        )
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            if (!surfaceReady || !::player.isInitialized) {
                try {
                    Thread.sleep(16)
                } catch (_: InterruptedException) {
                }
                last = System.nanoTime()
                continue
            }
            val now = System.nanoTime()
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            last = now
            update(dt)
            drawFrame()
        }
    }

    private fun update(dt: Float) {
        if (gameOver) return

        timeLeft -= dt
        if (timeLeft <= 0f) {
            timeLeft = 0f
            endMatch(timerEnded = true)
            return
        }

        if (bannerTimer > 0f) bannerTimer -= dt
        if (attackCooldown > 0f) attackCooldown -= dt

        updateActorTimers(player, dt)
        rivals.forEach { updateActorTimers(it, dt) }

        // Player move
        val pSpeed = playerEffectiveSpeed(player)
        player.x = (player.x + moveX * pSpeed * dt).coerceIn(player.radius, mapW - player.radius)
        player.y = (player.y + moveY * pSpeed * dt).coerceIn(player.radius + 40f, mapH - player.radius - 40f)
        updateCamera(dt)

        updateRivals(dt)
        updateCoins(dt)
        updateCannons(dt)
        updateFireballs(dt)
        updateDragons(dt)
        updateFx(dt)

        spawnCoinTimer -= dt
        if (spawnCoinTimer <= 0f) {
            spawnCoin()
            spawnCoinTimer = Random.nextFloat() * 0.7f + 0.45f
        }
        spawnDragonTimer -= dt
        if (spawnDragonTimer <= 0f) {
            if (dragons.none { it.kind == DragonKind.BLUE_SPEED }) {
                spawnDragon(DragonKind.BLUE_SPEED)
            } else {
                spawnDragon(randomAbilityDragon())
            }
            spawnDragonTimer = Random.nextFloat() * 8f + 10f
        }

        collectCoins(player)
        rivals.forEach { collectCoins(it) }
        checkFireballHits()
        checkDragonTouches()
    }

    private fun updateActorTimers(a: Actor, dt: Float) {
        if (a.invuln > 0f) a.invuln -= dt
        if (a.stealTimer > 0f) a.stealTimer -= dt
        if (a.shieldTimer > 0f) a.shieldTimer -= dt
        if (a.magnetTimer > 0f) a.magnetTimer -= dt
        if (a.rageTimer > 0f) a.rageTimer -= dt
        if (a.slowTimer > 0f) a.slowTimer -= dt
        // Keep steal effect topped when attacking recently is handled in performAttack
        if (a.speed > PLAYER_BASE_SPEED * 1.45f && a.isPlayer) {
            // blue dragon sets speed; decay slowly back if no refresh
        }
    }

    private fun playerEffectiveSpeed(a: Actor): Float {
        var s = a.speed
        if (a.rageTimer > 0f) s *= 1.25f
        if (a.slowTimer > 0f) s *= 0.7f
        return s
    }

    private fun updateRivals(dt: Float) {
        for (r in rivals) {
            r.aiThink -= dt
            if (r.aiThink <= 0f) {
                r.aiThink = Random.nextFloat() * 0.6f + 0.35f
                val nearest = coins.minByOrNull { hypot(it.x - r.x, it.y - r.y) }
                if (nearest != null && Random.nextFloat() < 0.75f) {
                    r.targetX = nearest.x
                    r.targetY = nearest.y
                } else {
                    r.targetX = Random.nextFloat() * mapW
                    r.targetY = Random.nextFloat() * (mapH - 120f) + 60f
                }
            }
            val dx = r.targetX - r.x
            val dy = r.targetY - r.y
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val spd = playerEffectiveSpeed(r)
            r.x = (r.x + dx / len * spd * dt).coerceIn(r.radius, mapW - r.radius)
            r.y = (r.y + dy / len * spd * dt).coerceIn(r.radius + 40f, mapH - r.radius - 40f)
        }
    }

    private fun updateCoins(dt: Float) {
        for (c in coins) {
            c.spin += dt * 4f
            if (player.magnetTimer > 0f) {
                val dx = player.x - c.x
                val dy = player.y - c.y
                val d = hypot(dx, dy)
                if (d < 220f && d > 1f) {
                    c.x += dx / d * 260f * dt
                    c.y += dy / d * 260f * dt
                }
            }
        }
    }

    private fun updateCannons(dt: Float) {
        for (cannon in cannons) {
            cannon.angle = atan2(player.y - cannon.y, player.x - cannon.x)
            cannon.cooldown -= dt
            if (cannon.cooldown <= 0f) {
                fireCannon(cannon)
                cannon.cooldown = CANNON_FIRE_INTERVAL
            }
        }
    }

    private fun updateFireballs(dt: Float) {
        val slow = player.slowTimer > 0f
        val mul = if (slow) 0.55f else 1f
        val it = fireballs.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.x += f.vx * dt * mul
            f.y += f.vy * dt * mul
            if (f.x < -80f || f.x > mapW + 80f || f.y < -80f || f.y > mapH + 80f) {
                it.remove()
            }
        }
    }

    private fun updateDragons(dt: Float) {
        for (d in dragons) {
            d.pulse += dt * 5f
            d.x += d.vx * dt
            d.y += d.vy * dt
            if (d.x < d.radius || d.x > mapW - d.radius) d.vx *= -1f
            if (d.y < d.radius + 40f || d.y > mapH - d.radius - 40f) d.vy *= -1f
            d.x = d.x.coerceIn(d.radius, mapW - d.radius)
            d.y = d.y.coerceIn(d.radius + 40f, mapH - d.radius - 40f)
            // Blue dragon is 2x player base speed — keep magnitude stable
            if (d.kind == DragonKind.BLUE_SPEED) {
                val target = PLAYER_BASE_SPEED * 2f
                val cur = hypot(d.vx, d.vy).coerceAtLeast(1f)
                d.vx = d.vx / cur * target
                d.vy = d.vy / cur * target
            }
        }
    }

    private fun updateFx(dt: Float) {
        attackFx.removeAll { fx ->
            fx.life -= dt
            fx.life <= 0f
        }
        floatTexts.removeAll { t ->
            t.life -= dt
            t.y -= 40f * dt
            t.life <= 0f
        }
    }

    private fun collectCoins(a: Actor) {
        val it = coins.iterator()
        while (it.hasNext()) {
            val c = it.next()
            if (hypot(c.x - a.x, c.y - a.y) < a.radius + c.radius) {
                a.coins += 1
                it.remove()
                if (a.isPlayer) {
                    floatTexts += FloatingText(c.x, c.y, "+1", Color.rgb(255, 215, 64))
                }
            }
        }
    }

    private fun checkFireballHits() {
        val all = listOf(player) + rivals
        val it = fireballs.iterator()
        while (it.hasNext()) {
            val f = it.next()
            var hit = false
            for (a in all) {
                if (a.invuln > 0f) continue
                if (hypot(f.x - a.x, f.y - a.y) < a.radius + f.radius) {
                    if (a.shieldTimer > 0f) {
                        floatTexts += FloatingText(a.x, a.y - 30f, "Blocked!", Color.rgb(120, 255, 180))
                        hit = true
                        break
                    }
                    a.lives -= 1
                    a.invuln = INVULN_SECONDS
                    floatTexts += FloatingText(a.x, a.y - 30f, "-1 Life", Color.rgb(255, 80, 80))
                    if (a.isPlayer && a.lives <= 0) {
                        endMatch(timerEnded = false)
                    }
                    if (!a.isPlayer && a.lives <= 0) {
                        a.lives = 0
                        // drop some coins
                        repeat(min(3, a.coins)) {
                            coins += Coin(a.x + Random.nextFloat() * 40f - 20f, a.y + Random.nextFloat() * 40f - 20f)
                        }
                        a.coins = max(0, a.coins - 3)
                        a.lives = 3
                        a.x = Random.nextFloat() * (mapW - 80f) + 40f
                        a.y = Random.nextFloat() * (mapH - 160f) + 80f
                        a.invuln = 2f
                    }
                    hit = true
                    break
                }
            }
            if (hit) it.remove()
        }
    }

    private fun checkDragonTouches() {
        val it = dragons.iterator()
        while (it.hasNext()) {
            val d = it.next()
            if (hypot(d.x - player.x, d.y - player.y) < player.radius + d.radius) {
                applyDragon(d.kind)
                it.remove()
            }
        }
    }

    private fun applyDragon(kind: DragonKind) {
        when (kind) {
            DragonKind.BLUE_SPEED -> {
                player.speed = PLAYER_BASE_SPEED * 1.5f
                messageBanner = "Blue Dragon! +50% speed"
                bannerTimer = 2.5f
                floatTexts += FloatingText(player.x, player.y - 40f, "Speed +50%", Color.rgb(80, 180, 255))
            }
            DragonKind.GOLD_MAGNET -> {
                player.magnetTimer = 10f
                messageBanner = "Gold Dragon! Coin magnet"
                bannerTimer = 2.5f
            }
            DragonKind.GREEN_SHIELD -> {
                player.shieldTimer = 8f
                messageBanner = "Green Dragon! Shield"
                bannerTimer = 2.5f
            }
            DragonKind.PURPLE_SLOW_TIME -> {
                player.slowTimer = 8f
                messageBanner = "Purple Dragon! Fireballs slowed"
                bannerTimer = 2.5f
            }
            DragonKind.RED_RAGE -> {
                player.rageTimer = 8f
                player.stealTimer = STEAL_EFFECT_SECONDS
                messageBanner = "Red Dragon! Rage + Steal Coin"
                bannerTimer = 2.5f
            }
        }
    }

    private fun performAttack() {
        if (gameOver || attackCooldown > 0f) return
        attackCooldown = ATTACK_COOLDOWN
        val type = AttackType.entries.random()
        lastAttackLabel = type.label

        // Ensure steal-coin effect window (10 sec) when attacking
        if (player.stealTimer <= 0f) {
            player.stealTimer = STEAL_EFFECT_SECONDS
        }

        val target = rivals
            .filter { it.lives > 0 }
            .minByOrNull { hypot(it.x - player.x, it.y - player.y) }

        var hit = false
        if (target != null) {
            val dist = hypot(target.x - player.x, target.y - player.y)
            val inRange = dist <= type.range
            if (type.needsCloseRange && !inRange) {
                messageBanner = "${type.label} missed — too far!"
                bannerTimer = 1.6f
            } else if (!inRange) {
                messageBanner = "${type.label} missed!"
                bannerTimer = 1.4f
            } else {
                // Random miss chance even in range for throw-like chaos
                val missChance = if (type.needsCloseRange) 0.18f else 0.12f
                if (Random.nextFloat() < missChance) {
                    messageBanner = "${type.label} missed (random)!"
                    bannerTimer = 1.5f
                } else {
                    hit = true
                    if (player.stealTimer > 0f) {
                        val stolen = min(STEAL_AMOUNT, target.coins)
                        target.coins -= stolen
                        player.coins += stolen
                        floatTexts += FloatingText(
                            target.x,
                            target.y - 36f,
                            "Stole $stolen!",
                            Color.rgb(255, 215, 64)
                        )
                        messageBanner = "${type.label} hit! Stole $stolen coins"
                    } else {
                        messageBanner = "${type.label} hit!"
                    }
                    bannerTimer = 1.8f
                    target.invuln = 0.4f
                }
            }
            attackFx += AttackFx(
                x = if (hit) target.x else player.x + (target.x - player.x) * 0.5f,
                y = if (hit) target.y else player.y + (target.y - player.y) * 0.5f,
                type = type,
                hit = hit
            )
        } else {
            messageBanner = "${type.label} — no target"
            bannerTimer = 1.2f
            attackFx += AttackFx(player.x, player.y - 40f, type, hit = false)
        }
    }

    private fun endMatch(timerEnded: Boolean) {
        gameOver = true
        wonByTimer = timerEnded
        messageBanner = if (timerEnded) "Time's up!" else "Out of lives!"
        bannerTimer = 5f
    }

    private fun drawFrame() {
        var canvas: Canvas? = null
        try {
            canvas = holderRef.lockCanvas()
            if (canvas != null) {
                drawGame(canvas)
            }
        } finally {
            if (canvas != null) {
                try {
                    holderRef.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun drawGame(canvas: Canvas) {
        // Arena background
        paint.shader = RadialGradient(
            screenW * 0.5f,
            screenH * 0.35f,
            screenW * 0.85f,
            intArrayOf(Color.rgb(22, 70, 88), Color.rgb(8, 28, 38)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW, screenH, paint)
        paint.shader = null

        canvas.save()
        canvas.translate(-camX, -camY)

        // Soft ground pattern across the map
        paint.color = Color.argb(40, 255, 255, 255)
        paint.strokeWidth = 2f
        var gy = 0f
        while (gy < mapH) {
            canvas.drawLine(0f, gy, mapW, gy, paint)
            gy += 48f
        }
        paint.color = Color.argb(55, 255, 255, 255)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        canvas.drawRect(2f, 2f, mapW - 2f, mapH - 2f, paint)
        paint.style = Paint.Style.FILL

        coins.forEach { drawCoin(canvas, it) }
        dragons.forEach { drawDragon(canvas, it) }
        cannons.forEach { drawCannon(canvas, it) }
        fireballs.forEach { drawFireball(canvas, it) }
        rivals.forEach { drawActor(canvas, it) }
        drawActor(canvas, player)
        attackFx.forEach { drawAttackFx(canvas, it) }
        floatTexts.forEach { drawFloatText(canvas, it) }

        canvas.restore()

        drawHud(canvas)
        drawControls(canvas)
        if (gameOver) drawGameOver(canvas)
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 200, 40)
        canvas.drawCircle(c.x, c.y, c.radius, paint)
        paint.color = Color.rgb(255, 230, 120)
        canvas.drawCircle(c.x - 3f, c.y - 3f, c.radius * 0.45f, paint)
        paint.color = Color.rgb(180, 120, 20)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(c.x, c.y, c.radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawFireball(canvas: Canvas, f: Fireball) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(220, 0, 0)
        canvas.drawCircle(f.x, f.y, f.radius, paint)
        paint.color = Color.rgb(255, 40, 40)
        canvas.drawCircle(f.x, f.y, f.radius * 0.45f, paint)
    }

    private fun drawCannon(canvas: Canvas, cannon: Cannon) {
        canvas.save()
        canvas.rotate(Math.toDegrees(cannon.angle.toDouble()).toFloat(), cannon.x, cannon.y)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(105, 115, 125)
        canvas.drawRoundRect(
            cannon.x - 4f,
            cannon.y - 13f,
            cannon.x + 48f,
            cannon.y + 13f,
            7f,
            7f,
            paint
        )
        paint.color = Color.rgb(55, 60, 68)
        canvas.drawCircle(cannon.x, cannon.y, 29f, paint)
        paint.color = Color.rgb(190, 25, 25)
        canvas.drawCircle(cannon.x, cannon.y, 18f, paint)
        canvas.restore()
    }

    private fun drawDragon(canvas: Canvas, d: Dragon) {
        val base = when (d.kind) {
            DragonKind.BLUE_SPEED -> Color.rgb(70, 160, 255)
            DragonKind.GOLD_MAGNET -> Color.rgb(255, 200, 50)
            DragonKind.GREEN_SHIELD -> Color.rgb(70, 220, 130)
            DragonKind.PURPLE_SLOW_TIME -> Color.rgb(180, 100, 255)
            DragonKind.RED_RAGE -> Color.rgb(255, 70, 90)
        }
        val pulse = 1f + 0.08f * sin(d.pulse)
        paint.style = Paint.Style.FILL
        paint.color = base
        canvas.drawCircle(d.x, d.y, d.radius * pulse, paint)
        // Wings
        paint.color = Color.argb(180, Color.red(base), Color.green(base), Color.blue(base))
        val wing = Path()
        wing.moveTo(d.x, d.y)
        wing.lineTo(d.x - 50f, d.y - 10f)
        wing.lineTo(d.x - 20f, d.y + 18f)
        wing.close()
        canvas.drawPath(wing, paint)
        val wing2 = Path()
        wing2.moveTo(d.x, d.y)
        wing2.lineTo(d.x + 50f, d.y - 10f)
        wing2.lineTo(d.x + 20f, d.y + 18f)
        wing2.close()
        canvas.drawPath(wing2, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(d.x - 10f, d.y - 6f, 5f, paint)
        canvas.drawCircle(d.x + 10f, d.y - 6f, 5f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(d.x - 10f, d.y - 6f, 2.5f, paint)
        canvas.drawCircle(d.x + 10f, d.y - 6f, 2.5f, paint)

        textPaint.textSize = 22f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        val label = when (d.kind) {
            DragonKind.BLUE_SPEED -> "BLUE"
            DragonKind.GOLD_MAGNET -> "MAGNET"
            DragonKind.GREEN_SHIELD -> "SHIELD"
            DragonKind.PURPLE_SLOW_TIME -> "SLOW"
            DragonKind.RED_RAGE -> "RAGE"
        }
        canvas.drawText(label, d.x, d.y + d.radius + 22f, textPaint)
    }

    private fun drawActor(canvas: Canvas, a: Actor) {
        if (a.invuln > 0f && ((a.invuln * 20).toInt() % 2 == 0)) {
            // flicker
        } else {
            if (a.shieldTimer > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.color = Color.argb(200, 100, 255, 180)
                canvas.drawCircle(a.x, a.y, a.radius + 10f, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = a.color
            canvas.drawCircle(a.x, a.y, a.radius, paint)
            paint.color = Color.argb(120, 255, 255, 255)
            canvas.drawCircle(a.x - 6f, a.y - 8f, a.radius * 0.35f, paint)
        }
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 20f
        textPaint.color = Color.WHITE
        val tag = if (a.isPlayer) "YOU" else "RIVAL"
        canvas.drawText(tag, a.x, a.y + a.radius + 20f, textPaint)
        textPaint.color = Color.rgb(255, 220, 80)
        canvas.drawText("${a.coins}", a.x, a.y + 8f, textPaint)
    }

    private fun drawAttackFx(canvas: Canvas, fx: AttackFx) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = fx.type.color
        val r = 30f + (0.45f - fx.life) * 80f
        canvas.drawCircle(fx.x, fx.y, r, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        textPaint.color = fx.type.color
        canvas.drawText(fx.type.label, fx.x, fx.y - r - 8f, textPaint)
    }

    private fun drawFloatText(canvas: Canvas, t: FloatingText) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        textPaint.color = Color.argb((t.life * 255).toInt().coerceIn(0, 255), Color.red(t.color), Color.green(t.color), Color.blue(t.color))
        canvas.drawText(t.text, t.x, t.y, textPaint)
    }

    private fun drawHud(canvas: Canvas) {
        // Top bar
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRect(0f, 0f, screenW, 64f, paint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 28f
        textPaint.color = Color.rgb(255, 215, 64)
        canvas.drawText("Coins ${player.coins}", 24f, 42f, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = Color.WHITE
        val mins = (timeLeft / 60).toInt()
        val secs = (timeLeft % 60).toInt()
        canvas.drawText("Time %d:%02d".format(mins, secs), screenW * 0.5f, 42f, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.rgb(255, 120, 120)
        canvas.drawText("Lives ${player.lives}", screenW - 24f, 42f, textPaint)

        // Effect chips
        var chipX = 24f
        val chipY = 78f
        fun chip(label: String, color: Int, active: Boolean) {
            if (!active) return
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
            val w = textPaint.measureText(label) + 24f
            textPaint.textSize = 20f
            canvas.drawRoundRect(chipX, chipY, chipX + w, chipY + 34f, 12f, 12f, paint)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.BLACK
            canvas.drawText(label, chipX + 12f, chipY + 24f, textPaint)
            chipX += w + 10f
        }
        chip("STEAL ${player.stealTimer.toInt()}s", Color.rgb(255, 200, 80), player.stealTimer > 0f)
        chip("SPEED", Color.rgb(80, 180, 255), player.speed > PLAYER_BASE_SPEED * 1.2f)
        chip("MAGNET", Color.rgb(255, 210, 60), player.magnetTimer > 0f)
        chip("SHIELD", Color.rgb(80, 230, 140), player.shieldTimer > 0f)
        chip("SLOW", Color.rgb(180, 120, 255), player.slowTimer > 0f)
        chip("RAGE", Color.rgb(255, 90, 90), player.rageTimer > 0f)

        if (bannerTimer > 0f) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 26f
            textPaint.color = Color.WHITE
            paint.color = Color.argb(160, 0, 0, 0)
            val tw = textPaint.measureText(messageBanner) + 40f
            canvas.drawRoundRect(
                screenW * 0.5f - tw / 2f,
                120f,
                screenW * 0.5f + tw / 2f,
                160f,
                16f,
                16f,
                paint
            )
            canvas.drawText(messageBanner, screenW * 0.5f, 148f, textPaint)
        }

        if (lastAttackLabel.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 18f
            textPaint.color = Color.argb(200, 200, 230, 255)
            canvas.drawText("Last attack: $lastAttackLabel", screenW * 0.5f, screenH - 28f, textPaint)
        }
    }

    private fun drawControls(canvas: Canvas) {
        // Joystick base
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(joyCx, joyCy, 100f, paint)
        paint.color = Color.argb(180, 255, 255, 255)
        canvas.drawCircle(joyKnobX, joyKnobY, 42f, paint)

        // Attack button
        paint.color = if (attackCooldown > 0f) Color.argb(120, 180, 80, 80) else Color.argb(200, 230, 70, 70)
        canvas.drawOval(attackBtn, paint)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        textPaint.color = Color.WHITE
        canvas.drawText("ATK", attackBtn.centerX(), attackBtn.centerY() + 10f, textPaint)
    }

    private fun drawGameOver(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 0, 0, 0)
        canvas.drawRect(0f, 0f, screenW, screenH, paint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 48f
        textPaint.color = Color.rgb(255, 215, 64)
        canvas.drawText("Game Over", screenW * 0.5f, screenH * 0.32f, textPaint)

        textPaint.textSize = 30f
        textPaint.color = Color.WHITE
        val reason = if (wonByTimer) "Timer finished!" else "You lost all lives!"
        canvas.drawText(reason, screenW * 0.5f, screenH * 0.40f, textPaint)
        textPaint.color = Color.rgb(255, 220, 100)
        canvas.drawText("Coins collected: ${player.coins}", screenW * 0.5f, screenH * 0.48f, textPaint)

        paint.color = Color.rgb(70, 160, 255)
        canvas.drawRoundRect(menuBtn, 18f, 18f, paint)
        paint.color = Color.rgb(46, 204, 113)
        canvas.drawRoundRect(againBtn, 18f, 18f, paint)
        textPaint.color = Color.WHITE
        textPaint.textSize = 28f
        canvas.drawText("Back to Menu", menuBtn.centerX(), menuBtn.centerY() + 10f, textPaint)
        canvas.drawText("Play Again", againBtn.centerX(), againBtn.centerY() + 10f, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (gameOver) {
                    if (menuBtn.contains(x, y)) {
                        onExitToMenu()
                        return true
                    }
                    if (againBtn.contains(x, y)) {
                        startMatch()
                        return true
                    }
                    return true
                }
                if (attackBtn.contains(x, y)) {
                    performAttack()
                    return true
                }
                if (hypot(x - joyCx, y - joyCy) <= 140f || x < screenW * 0.45f) {
                    joyActive = true
                    joyId = id
                    updateJoystick(x, y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gameOver && joyActive) {
                    val idx = pointerIndex(event, joyId)
                    if (idx >= 0) updateJoystick(event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id == joyId) {
                    joyActive = false
                    joyId = -1
                    joyKnobX = joyCx
                    joyKnobY = joyCy
                    moveX = 0f
                    moveY = 0f
                }
            }
        }
        return true
    }

    private fun pointerIndex(event: MotionEvent, id: Int): Int {
        for (i in 0 until event.pointerCount) {
            if (event.getPointerId(i) == id) return i
        }
        return -1
    }

    private fun updateJoystick(x: Float, y: Float) {
        val dx = x - joyCx
        val dy = y - joyCy
        val maxR = 78f
        val len = hypot(dx, dy)
        if (len > maxR) {
            joyKnobX = joyCx + dx / len * maxR
            joyKnobY = joyCy + dy / len * maxR
            moveX = dx / len
            moveY = dy / len
        } else {
            joyKnobX = x
            joyKnobY = y
            moveX = if (len < 8f) 0f else dx / maxR
            moveY = if (len < 8f) 0f else dy / maxR
        }
    }
}
