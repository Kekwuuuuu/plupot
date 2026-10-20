package com.plupot.game;

import com.plupot.core.GameContext;
import com.plupot.ui.Draw;
import com.plupot.ui.Fonts;
import com.plupot.ui.Palette;

import javax.swing.AbstractAction;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The gameplay surface. Everything is transparent except the C1 road on the
 * right side of the screen. The road is split into {@link #NUM_ALLEYS} columns;
 * obstacles fall down one alley at a time.
 *
 * <p><b>Rendering (macOS translucency note).</b> A {@code BufferStrategy} cannot
 * composite into a per-pixel-translucent window — its buffers carry no alpha,
 * so nothing ever reaches the screen. This class therefore paints through
 * Swing's normal {@code paintComponent} path, which <em>does</em> write into the
 * window's alpha surface. To keep the macOS compositor from tearing the
 * always-on-top translucent surface, every frame is first rendered into an
 * offscreen ARGB image and then blitted to the window in a <em>single</em>
 * {@code AlphaComposite.Src} draw — one atomic pixel write per frame instead of
 * dozens of drawing operations interleaved with the compositor.</p>
 *
 * <p>Gameplay: one fixed-size leader circle surrounds itself with
 * {@code pot - 1} followers packed into a fixed 30-cell hexagonal lattice.
 * Up to 30 followers display one circle each; beyond that, each cell shows
 * the number of followers stacked on it in its center. The lattice is drawn
 * relative to a single smoothed group center, so the whole formation glides
 * as one rigid body.</p>
 *
 * <p>The pot is a 64-bit value. It is only clamped at {@link Long#MAX_VALUE}
 * (via saturating multiply/add), so long runs will not freeze at the old
 * {@code Integer.MAX_VALUE} ceiling.</p>
 *
 * <p>Spawning: every spawn event rolls {@link #MIN_BARS_PER_SPAWN}..{@link
 * #MAX_BARS_PER_SPAWN} bars. When two are rolled they are placed in
 * <em>distinct</em> alleys and share a spawn <em>group</em> id. Each bar counts
 * toward the cycle total, so the last spawn is trimmed if fewer than the rolled
 * number of slots remain.</p>
 *
 * <p>Fight: after {@link #barsPerCycle} bars, a fight plays. That count is
 * re-rolled to a random value in {@code [MIN_BARS_PER_CYCLE, MAX_BARS_PER_CYCLE]}
 * each time a fight resolves. The enemy's number is derived from the maximum
 * pot the player could have achieved by hitting any subset of the cycle's
 * spawn-ordered bars without dropping to 0 mid-sequence. Bars that spawned
 * together may contribute at most one of their number to that subset. The two
 * formations then slide toward each other and their displayed units drain in
 * real time ({@code FIGHT_CLASH}); once the animation resolves,
 * {@code pot - enemyNumber} is applied.</p>
 *
 * <p>Score: a white counter sits at the top of the road bar, ticking
 * up by 1 every {@link #SCORE_INTERVAL} seconds. The current score is handed
 * to {@link GameContext} whenever the run ends.</p>
 *
 * <p>Progression: each completed fight shrinks the spawn interval and grows
 * the fall speed, bounded so neither ever hits an unwinnable extreme.</p>
 *
 * <p>Debug: press <b>X</b> to toggle a live info block bottom-left.</p>
 */
public final class OverlayPanel extends JPanel {

    // ------------------------------------------------------------------ config
    private static final int NUM_ALLEYS = 3;
    private static final int TICK_MILLIS = 16;
    private static final double MAX_DT = 0.1;

    private static final double HOLD_SECONDS = 2.0;
    private static final double RELEASE_RATE = 8.0;

    private static final long MIN_POT = 0L;

    private static final double DEFAULT_SPAWN_RATE = 5.0;
    private static final double MIN_SPAWN_RATE = 1.2;
    private static final double SPAWN_STEP = 0.15;

    private static final double OBSTACLE_SPEED_FRAC = 0.35;
    private static final double MAX_OBSTACLE_SPEED_FRAC = 0.875;
    private static final double SPEED_STEP = 0.009;

    private static final double OBSTACLE_HEIGHT_FRAC = 0.055;
    private static final double OBSTACLE_WIDTH_FRAC_OF_ALLEY = 0.85;
    private static final double PLAYER_SPEED_FRAC = 1.4;

    private static final float PLAYER_RADIUS_FRAC_OF_ALLEY = 0.13f;
    private static final float SPACING_FACTOR = 2.15f;
    private static final double FOLLOW_STIFFNESS = 10.0;
    private static final double ALPHA_STIFFNESS = 6.0;
    private static final float OUTSIDE_ALPHA = 1f / 3f;

    private static final int SLOT_COUNT = 30;
    private static final int MAX_RING = 3;
    private static final double SQRT3_2 = Math.sqrt(3.0) / 2.0;
    private static final List<int[]> HEX_ORDER = buildHexOrder(MAX_RING, SLOT_COUNT);

    // --- spawn configs
    private static final int MIN_BARS_PER_SPAWN = 1;
    private static final int MAX_BARS_PER_SPAWN = 2;

    // --- fight configs
    private static final int MIN_BARS_PER_CYCLE = 5;
    private static final int MAX_BARS_PER_CYCLE = 20;

    private static final double T_FIGHT_ENTER = 0.9;
    private static final double T_FIGHT_HOLD = 0.6;
    private static final double T_FIGHT_MOVE = 0.5;
    private static final double T_FIGHT_CLASH = 1.4;
    private static final double T_FIGHT_RESOLVE = 0.8;
    private static final double T_FIGHT_CLEAR = 0.6;
    private static final double FIGHT_STIFFNESS = 8.0;
    private static final float FIGHT_ENEMY_Y_FRAC = 0.35f;
    private static final float FIGHT_PLAYER_Y_FRAC = 0.62f;
    private static final float FIGHT_CLASH_PLAYER_Y_FRAC = 0.55f;
    private static final float FIGHT_CLASH_ENEMY_Y_FRAC = 0.42f;
    private static final float FIGHT_ENEMY_START_Y_FRAC = -0.30f;

    // --- score
    private static final double SCORE_INTERVAL = 0.1;

    // ------------------------------------------------------------------ state
    private enum Phase {
        PLAYING, FIGHT_ENTER, FIGHT_HOLD, FIGHT_MOVE, FIGHT_CLASH,
        FIGHT_RESOLVE, FIGHT_CLEAR
    }

    private final GameContext ctx;
    private final boolean translucent;
    private final Timer frameTimer;
    private final Set<Integer> heldKeys = new HashSet<>();
    private final List<Obstacle> obstacles = new ArrayList<>();
    private final List<Obstacle> cycleBars = new ArrayList<>();

    private final float[] slotAlpha = new float[SLOT_COUNT];
    private final long[] slotCount = new long[SLOT_COUNT];

    /** Offscreen frame; every repaint is rendered here and blitted in one go. */
    private BufferedImage offscreen;

    /** How many bars this cycle spawns before a fight triggers. Re-rolled after every fight. */
    private int barsPerCycle = MIN_BARS_PER_CYCLE;

    /** Monotonic spawn-group id; bars from one spawn event share it. */
    private int nextGroupId = 0;

    private double spawnRate = DEFAULT_SPAWN_RATE;
    private double spawnTimer = 0.0;
    private double obstacleSpeedFrac = OBSTACLE_SPEED_FRAC;

    private long pot = 1L;
    private long cycleStartPot = 1L;

    private int score = 0;
    private double scoreTimer = 0.0;

    private float playerCx = Float.NaN;
    private float playerY = Float.NaN;
    private float smoothCx = Float.NaN;

    private boolean initialized = false;
    private boolean gameOver = false;

    private double escAmount = 0.0;
    private boolean escClosePending = false;

    private long lastTickNanos = 0L;
    private boolean windowFocusHooked = false;

    // Fight state
    private Phase phase = Phase.PLAYING;
    private double phaseTime = 0.0;
    private long enemyNumber = 0L;
    private float enemyCx = 0f;
    private float enemyCy = 0f;
    private float enemyAlpha = 0f;
    private float enemyEnterFromY = 0f;
    private boolean pendingGameOver = false;
    private long fightOldPot = 0L;

    // Clash animation
    private float clashPotStart = 0f;
    private float clashEnemyStart = 0f;
    private float clashPotEnd = 0f;
    private float clashEnemyEnd = 0f;
    private float clashDisplayPot = 0f;
    private float clashDisplayEnemy = 0f;

    // Debug state
    private boolean debugVisible = false;
    private boolean xHeld = false;

    private long debugCalcStartPot = 0L;
    private long debugCalcHighest = 0L;
    private long debugCalcMaxRoll = 0L;
    private long debugCalcRoll = 0L;
    private long debugCalcEnemy = 0L;
    private String debugCalcBars = "-";
    private boolean debugCalcValid = false;

    public OverlayPanel(GameContext ctx, boolean translucent) {
        this.ctx = ctx;
        this.translucent = translucent;

        if (translucent) {
            setOpaque(false);
        } else {
            setOpaque(true);
            setBackground(Palette.C1);
        }
        setFocusable(true);

        bindKeys();

        frameTimer = new Timer(TICK_MILLIS, e -> tick());
        frameTimer.setCoalesce(true);

        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                heldKeys.clear();
                xHeld = false;
            }
        });
    }

    // ------------------------------------------------------------------ input

    private void bindKeys() {
        int[] codes = {
                KeyEvent.VK_A, KeyEvent.VK_D,
                KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT,
                KeyEvent.VK_ESCAPE
        };
        var inputMap = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        var actionMap = getActionMap();

        for (int code : codes) {
            final int key = code;
            inputMap.put(KeyStroke.getKeyStroke(key, 0, false), "down-" + key);
            inputMap.put(KeyStroke.getKeyStroke(key, 0, true), "up-" + key);

            actionMap.put("down-" + key, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    heldKeys.add(key);
                }
            });
            actionMap.put("up-" + key, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    heldKeys.remove(key);
                }
            });
        }

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, 0, false), "debug-toggle");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_X, 0, true), "debug-release");

        actionMap.put("debug-toggle", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!xHeld) {
                    xHeld = true;
                    debugVisible = !debugVisible;
                }
            }
        });
        actionMap.put("debug-release", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                xHeld = false;
            }
        });
    }

    // ------------------------------------------------------------------ loop

    private void tick() {
        long now = System.nanoTime();
        double dt = (now - lastTickNanos) / 1_000_000_000.0;
        lastTickNanos = now;
        if (dt > MAX_DT) {
            dt = MAX_DT;
        }
        if (dt < 0) {
            dt = 0;
        }

        updateGame(dt);
        updateEsc(dt);
        repaint();

        if (gameOver) {
            gameOver = false;
            frameTimer.stop();
            submitFinalScore();
            SwingUtilities.invokeLater(ctx::closeOverlay);
            return;
        }
        if (escClosePending) {
            escClosePending = false;
            frameTimer.stop();
            submitFinalScore();
            SwingUtilities.invokeLater(ctx::closeOverlay);
        }
    }

    /**
     * Hand the run's final score to the controller. Uses only the two methods
     * the {@link GameContext} interface exposes — {@code getHighScore()} and
     * {@code setHighScore(int)} — so it works against whatever concrete
     * controller implements the interface.
     */
    private void submitFinalScore() {
        if (score > ctx.getHighScore()) {
            ctx.setHighScore(score);
        }
    }

    // ------------------------------------------------------------------ update

    private void updateGame(double dt) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        BarBounds b = barBounds(w, h);

        if (!initialized) {
            playerCx = (b.left() + b.right()) / 2f;
            playerY = b.playerY();
            smoothCx = playerCx;
            initialized = true;
        }

        scoreTimer += dt;
        while (scoreTimer >= SCORE_INTERVAL) {
            scoreTimer -= SCORE_INTERVAL;
            score++;
        }

        phaseTime += dt;

        switch (phase) {
            case PLAYING -> updatePlaying(b, dt, h);
            case FIGHT_ENTER -> updateFightEnter(b, dt, h);
            case FIGHT_HOLD -> updateFightHold(b, dt, h);
            case FIGHT_MOVE -> updateFightMove(b, dt, h);
            case FIGHT_CLASH -> updateFightClash(b, dt, h);
            case FIGHT_RESOLVE -> updateFightResolve(b, dt, h);
            case FIGHT_CLEAR -> updateFightClear(b, dt, h);
        }

        updateFollowers(b, dt);
    }

    private void updatePlaying(BarBounds b, double dt, int h) {
        float lerp = (float) (1.0 - Math.exp(-FIGHT_STIFFNESS * dt));
        playerY += (b.playerY() - playerY) * lerp;

        int dir = 0;
        if (heldKeys.contains(KeyEvent.VK_A) || heldKeys.contains(KeyEvent.VK_LEFT)) {
            dir -= 1;
        }
        if (heldKeys.contains(KeyEvent.VK_D) || heldKeys.contains(KeyEvent.VK_RIGHT)) {
            dir += 1;
        }
        if (dir != 0) {
            float speed = b.barWidth() * (float) PLAYER_SPEED_FRAC;
            playerCx += dir * speed * dt;
            reclampPlayer(b);
        }

        if (cycleBars.size() < barsPerCycle) {
            spawnTimer += dt;
            if (spawnTimer >= spawnRate) {
                spawnTimer -= spawnRate;
                spawnObstacles(b);
            }
        }

        float obsSpeed = h * (float) obstacleSpeedFrac;
        for (Obstacle obs : obstacles) {
            obs.y += obsSpeed * dt;
        }

        if (pot > 0) {
            checkCollisions(b, h);
        }

        Iterator<Obstacle> it = obstacles.iterator();
        while (it.hasNext()) {
            Obstacle obs = it.next();
            if (obs.y > h + b.rowH() * 2f) {
                it.remove();
            }
        }

        if (!gameOver && cycleComplete(h)) {
            startFight(b, h);
        }
    }

    private boolean cycleComplete(int h) {
        if (cycleBars.size() < barsPerCycle) {
            return false;
        }
        for (Obstacle obs : cycleBars) {
            if (obs.y <= h) {
                return false;
            }
        }
        return true;
    }

    private void updateFightEnter(BarBounds b, double dt, int h) {
        float lerp = (float) (1.0 - Math.exp(-FIGHT_STIFFNESS * dt));
        playerY += (b.playerY() - playerY) * lerp;

        int dir = 0;
        if (heldKeys.contains(KeyEvent.VK_A) || heldKeys.contains(KeyEvent.VK_LEFT)) dir -= 1;
        if (heldKeys.contains(KeyEvent.VK_D) || heldKeys.contains(KeyEvent.VK_RIGHT)) dir += 1;
        if (dir != 0) {
            float speed = b.barWidth() * (float) PLAYER_SPEED_FRAC;
            playerCx += dir * speed * dt;
            reclampPlayer(b);
        }

        float fightY = h * FIGHT_ENEMY_Y_FRAC;
        float t = (float) Math.min(1.0, phaseTime / T_FIGHT_ENTER);
        float e = 1f - (1f - t) * (1f - t) * (1f - t);
        enemyCx = (b.left() + b.right()) / 2f;
        enemyCy = enemyEnterFromY + (fightY - enemyEnterFromY) * e;
        enemyAlpha = 1f;

        if (phaseTime >= T_FIGHT_ENTER) {
            advancePhase(Phase.FIGHT_HOLD);
        }
    }

    private void updateFightHold(BarBounds b, double dt, int h) {
        float lerp = (float) (1.0 - Math.exp(-FIGHT_STIFFNESS * dt));
        playerY += (b.playerY() - playerY) * lerp;
        enemyCx = (b.left() + b.right()) / 2f;
        enemyCy = h * FIGHT_ENEMY_Y_FRAC;

        int dir = 0;
        if (heldKeys.contains(KeyEvent.VK_A) || heldKeys.contains(KeyEvent.VK_LEFT)) dir -= 1;
        if (heldKeys.contains(KeyEvent.VK_D) || heldKeys.contains(KeyEvent.VK_RIGHT)) dir += 1;
        if (dir != 0) {
            float speed = b.barWidth() * (float) PLAYER_SPEED_FRAC;
            playerCx += dir * speed * dt;
            reclampPlayer(b);
        }

        if (phaseTime >= T_FIGHT_HOLD) {
            advancePhase(Phase.FIGHT_MOVE);
        }
    }

    private void updateFightMove(BarBounds b, double dt, int h) {
        float lerp = (float) (1.0 - Math.exp(-FIGHT_STIFFNESS * dt));
        float targetCx = (b.left() + b.right()) / 2f;
        float targetY = h * FIGHT_PLAYER_Y_FRAC;
        playerCx += (targetCx - playerCx) * lerp;
        playerY += (targetY - playerY) * lerp;
        enemyCx = (b.left() + b.right()) / 2f;
        enemyCy = h * FIGHT_ENEMY_Y_FRAC;

        if (phaseTime >= T_FIGHT_MOVE) {
            fightOldPot = pot;
            clashPotStart = (float) pot;
            clashEnemyStart = (float) enemyNumber;
            long unitsLost = Math.min(pot, enemyNumber);
            clashPotEnd = clashPotStart - unitsLost;
            clashEnemyEnd = clashEnemyStart - unitsLost;
            clashDisplayPot = clashPotStart;
            clashDisplayEnemy = clashEnemyStart;
            advancePhase(Phase.FIGHT_CLASH);
        }
    }

    private void updateFightClash(BarBounds b, double dt, int h) {
        float t = (float) Math.min(1.0, phaseTime / T_FIGHT_CLASH);
        float e = t * t * (3f - 2f * t);

        float centerCx = (b.left() + b.right()) / 2f;
        float playerStartY = h * FIGHT_PLAYER_Y_FRAC;
        float playerEndY = h * FIGHT_CLASH_PLAYER_Y_FRAC;
        float enemyStartY = h * FIGHT_ENEMY_Y_FRAC;
        float enemyEndY = h * FIGHT_CLASH_ENEMY_Y_FRAC;

        playerCx = centerCx;
        playerY = playerStartY + (playerEndY - playerStartY) * e;
        enemyCx = centerCx;
        enemyCy = enemyStartY + (enemyEndY - enemyStartY) * e;

        clashDisplayPot = clashPotStart + (clashPotEnd - clashPotStart) * e;
        clashDisplayEnemy = clashEnemyStart + (clashEnemyEnd - clashEnemyStart) * e;

        if (phaseTime >= T_FIGHT_CLASH) {
            long newPot = fightOldPot - enemyNumber;
            if (newPot <= 0) {
                pot = 0L;
                pendingGameOver = true;
            } else {
                pot = newPot;
            }
            advancePhase(Phase.FIGHT_RESOLVE);
        }
    }

    private void updateFightResolve(BarBounds b, double dt, int h) {
        float centerCx = (b.left() + b.right()) / 2f;
        playerCx = centerCx;
        playerY = h * FIGHT_CLASH_PLAYER_Y_FRAC;
        enemyCx = centerCx;
        enemyCy = h * FIGHT_CLASH_ENEMY_Y_FRAC;

        if (phaseTime >= T_FIGHT_RESOLVE) {
            advancePhase(Phase.FIGHT_CLEAR);
        }
    }

    private void updateFightClear(BarBounds b, double dt, int h) {
        float t = (float) Math.min(1.0, phaseTime / T_FIGHT_CLEAR);
        enemyAlpha = 1f - t;
        float centerCx = (b.left() + b.right()) / 2f;
        enemyCx = centerCx;
        enemyCy = h * FIGHT_CLASH_ENEMY_Y_FRAC;

        float lerp = (float) (1.0 - Math.exp(-FIGHT_STIFFNESS * dt));
        playerCx = centerCx;
        playerY += (b.playerY() - playerY) * lerp;

        if (phaseTime >= T_FIGHT_CLEAR) {
            enemyNumber = 0L;
            enemyAlpha = 0f;
            if (pendingGameOver) {
                pendingGameOver = false;
                gameOver = true;
            } else {
                spawnRate = Math.max(MIN_SPAWN_RATE, spawnRate - SPAWN_STEP);
                obstacleSpeedFrac = Math.min(MAX_OBSTACLE_SPEED_FRAC,
                        obstacleSpeedFrac + SPEED_STEP);
                barsPerCycle = ThreadLocalRandom.current()
                        .nextInt(MIN_BARS_PER_CYCLE, MAX_BARS_PER_CYCLE + 1);
                advancePhase(Phase.PLAYING);
            }
        }
    }

    private void advancePhase(Phase next) {
        phase = next;
        phaseTime = 0.0;
    }

    // ------------------------------------------------------------------ display helpers

    private long currentDisplayPot() {
        if (phase == Phase.FIGHT_CLASH) {
            return Math.max(0L, (long) clashDisplayPot);
        }
        return Math.max(0L, pot);
    }

    private long currentDisplayEnemy() {
        if (phase == Phase.FIGHT_CLASH) {
            return Math.max(0L, (long) clashDisplayEnemy);
        }
        if (phase == Phase.FIGHT_RESOLVE || phase == Phase.FIGHT_CLEAR) {
            return Math.max(0L, enemyNumber - fightOldPot);
        }
        return Math.max(0L, enemyNumber);
    }

    // ------------------------------------------------------------------ fight calc

    private void startFight(BarBounds b, int h) {
        debugCalcBars = joinBarText(cycleBars);
        enemyNumber = computeEnemyNumber(cycleStartPot, cycleBars);
        cycleBars.clear();
        obstacles.clear();
        spawnTimer = 0.0;

        enemyCx = (b.left() + b.right()) / 2f;
        enemyEnterFromY = h * FIGHT_ENEMY_START_Y_FRAC;
        enemyCy = enemyEnterFromY;
        enemyAlpha = 1f;

        pendingGameOver = false;
        fightOldPot = pot;
        clashPotStart = 0f;
        clashEnemyStart = 0f;
        clashPotEnd = 0f;
        clashEnemyEnd = 0f;
        clashDisplayPot = 0f;
        clashDisplayEnemy = 0f;

        advancePhase(Phase.FIGHT_ENTER);
    }

    private long computeEnemyNumber(long startPot, List<Obstacle> bars) {
        debugCalcStartPot = startPot;

        if (startPot <= 0) {
            debugCalcHighest = 0L;
            debugCalcMaxRoll = 0L;
            debugCalcRoll    = 0L;
            debugCalcEnemy   = 0L;
            debugCalcValid   = true;
            return 0L;
        }

        long highest = bestFightResult(startPot, bars);
        debugCalcHighest = highest;

        if (highest <= 0) {
            debugCalcMaxRoll = 0L;
            debugCalcRoll    = 0L;
            debugCalcEnemy   = 0L;
            debugCalcValid   = true;
            return 0L;
        }

        long maxRoll = highest / 2L;
        if (maxRoll < 1L) maxRoll = 1L;
        debugCalcMaxRoll = maxRoll;

        long roll = ThreadLocalRandom.current().nextLong(1L, maxRoll + 1L);
        debugCalcRoll = roll;

        long enemy = highest - roll;
        if (enemy < 0) enemy = 0L;
        debugCalcEnemy = enemy;
        debugCalcValid = true;
        return enemy;
    }

    private long bestFightResult(long startPot, List<Obstacle> bars) {
        long[] best = {startPot};
        searchSubsequences(startPot, bars, 0, best, -1);
        return best[0];
    }

    /**
     * Depth-first search over subsequences of {@code bars}, in spawn order.
     * {@code lastGroup} carries the spawn-group id of the most recently chosen
     * bar so two bars from the same spawn event can never both be used.
     */
    private void searchSubsequences(long pot, List<Obstacle> bars, int from,
                                    long[] best, int lastGroup) {
        if (pot > best[0]) best[0] = pot;
        for (int i = from; i < bars.size(); i++) {
            Obstacle obs = bars.get(i);
            if (obs.group == lastGroup) continue;
            long next = applyFightOp(pot, obs);
            if (next <= 0) continue;
            searchSubsequences(next, bars, i + 1, best, obs.group);
        }
    }

    private long applyFightOp(long pot, Obstacle obs) {
        return switch (obs.op) {
            case '+' -> satAdd(pot, obs.value);
            case '-' -> pot - obs.value;
            case '*' -> satMul(pot, obs.value);
            case '/' -> obs.value == 0 ? pot : pot / obs.value;
            default -> pot;
        };
    }

    private long applyMath(Obstacle obs) {
        return switch (obs.op) {
            case '+' -> satAdd(pot, obs.value);
            case '-' -> pot - obs.value;
            case '*' -> satMul(pot, obs.value);
            case '/' -> (obs.value == 0) ? pot : pot / obs.value;
            default -> pot;
        };
    }

    private static long satAdd(long a, int b) {
        try {
            return Math.addExact(a, (long) b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long satMul(long a, int b) {
        try {
            return Math.multiplyExact(a, (long) b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------ obstacles

    private void updateEsc(double dt) {
        boolean held = heldKeys.contains(KeyEvent.VK_ESCAPE);
        if (held) {
            escAmount += dt / HOLD_SECONDS;
            if (escAmount >= 1.0) {
                escAmount = 1.0;
                escClosePending = true;
            }
        } else if (escAmount > 0.0) {
            escAmount -= dt * RELEASE_RATE;
            if (escAmount < 0.0) escAmount = 0.0;
        }
    }

    /**
     * One spawn event. Rolls 1..{@link #MAX_BARS_PER_SPAWN} bars, places them
     * in distinct alleys, and trims to the number of slots left in the cycle.
     * All bars born in one event share a spawn-group id.
     */
    private void spawnObstacles(BarBounds b) {
        int remaining = barsPerCycle - cycleBars.size();
        if (remaining <= 0) return;

        int count = ThreadLocalRandom.current()
                .nextInt(MIN_BARS_PER_SPAWN, MAX_BARS_PER_SPAWN + 1);
        count = Math.min(count, remaining);

        if (cycleBars.isEmpty()) {
            cycleStartPot = pot;
        }

        int groupId = nextGroupId++;
        int[] alleys = pickDistinctAlleys(count);
        for (int alley : alleys) {
            char[] ops = {'+', '-', '*', '/'};
            char op = ops[ThreadLocalRandom.current().nextInt(ops.length)];
            int value = ThreadLocalRandom.current().nextInt(1, 11);

            Obstacle obs = new Obstacle(alley, op, value, groupId);
            obs.y = -b.rowH();
            obstacles.add(obs);
            cycleBars.add(obs);
        }
    }

    /** Picks {@code count} distinct alley indices out of {@code [0, NUM_ALLEYS)}. */
    private static int[] pickDistinctAlleys(int count) {
        count = Math.min(count, NUM_ALLEYS);
        int[] pool = new int[NUM_ALLEYS];
        for (int i = 0; i < NUM_ALLEYS; i++) {
            pool[i] = i;
        }

        // Partial Fisher-Yates: shuffle the first `count` positions.
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            int j = rnd.nextInt(i, NUM_ALLEYS);
            int tmp = pool[i];
            pool[i] = pool[j];
            pool[j] = tmp;
        }

        int[] result = new int[count];
        System.arraycopy(pool, 0, result, 0, count);
        return result;
    }

    private void checkCollisions(BarBounds b, int h) {
        if (pot <= 0) return;
        float r = playerRadius(b);
        float py = playerY;
        float leader = playerCx;

        for (Obstacle obs : obstacles) {
            if (obs.consumed) continue;
            float obsTop = (float) obs.y;
            float obsBot = obsTop + b.rowH();
            if (obsBot < py - r || obsTop > py + r) continue;

            float alleyLeft = b.left() + obs.alley * b.alleyWidth();
            float obsW = b.alleyWidth() * (float) OBSTACLE_WIDTH_FRAC_OF_ALLEY;
            float obsLeft = alleyLeft + (b.alleyWidth() - obsW) / 2f;
            float obsRight = obsLeft + obsW;

            if (leader + r >= obsLeft && leader - r <= obsRight) {
                obs.consumed = true;
                applyMathToPot(obs);
                if (pot <= 0) return;
            }
        }
    }

    private void applyMathToPot(Obstacle obs) {
        if (gameOver) return;
        long result = applyMath(obs);
        if (result < MIN_POT) result = MIN_POT;
        pot = result;

        if (pot <= 0) gameOver = true;
    }

    // ------------------------------------------------------------------ player chain

    private float playerRadius(BarBounds b) {
        return b.alleyWidth() * PLAYER_RADIUS_FRAC_OF_ALLEY;
    }

    private void reclampPlayer(BarBounds b) {
        float r = playerRadius(b);
        float minCx = b.left() + r;
        float maxCx = b.right() - r;
        if (minCx > maxCx) {
            playerCx = (b.left() + b.right()) / 2f;
        } else {
            playerCx = Math.max(minCx, Math.min(maxCx, playerCx));
        }
    }

    private static long countForSlot(int slot, long followerCount) {
        if (followerCount <= SLOT_COUNT) {
            return slot < followerCount ? 1L : 0L;
        }
        long base = followerCount / SLOT_COUNT;
        long rem = followerCount % SLOT_COUNT;
        return slot < rem ? base + 1L : base;
    }

    private static float offsetX(int k, float spacing) {
        int[] cell = HEX_ORDER.get(k);
        return spacing * (cell[0] + cell[1] * 0.5f);
    }

    private static float offsetY(int k, float spacing) {
        int[] cell = HEX_ORDER.get(k);
        return spacing * (float) SQRT3_2 * cell[1];
    }

    private void updateFollowers(BarBounds b, double dt) {
        float r = playerRadius(b);
        float spacing = r * SPACING_FACTOR;

        float followLerp = (float) (1.0 - Math.exp(-FOLLOW_STIFFNESS * dt));
        smoothCx += (playerCx - smoothCx) * followLerp;

        float alphaLerp = (float) (1.0 - Math.exp(-ALPHA_STIFFNESS * dt));
        long visualPot = currentDisplayPot();
        long followerCount = Math.max(0L, visualPot - 1L);

        for (int i = 0; i < SLOT_COUNT; i++) {
            long count = countForSlot(i, followerCount);
            slotCount[i] = count;

            float fx = smoothCx + offsetX(i, spacing);
            boolean outside = count > 0
                    && ((fx - r < b.left()) || (fx + r > b.right()));
            float target = count == 0 ? 0f : (outside ? OUTSIDE_ALPHA : 1f);
            slotAlpha[i] += (target - slotAlpha[i]) * alphaLerp;
        }
    }

    private static List<int[]> buildHexOrder(int maxRing, int limit) {
        List<int[]> cells = new ArrayList<>();
        for (int q = -maxRing; q <= maxRing; q++) {
            for (int r = -maxRing; r <= maxRing; r++) {
                if (q == 0 && r == 0) continue;
                if (Math.max(Math.abs(q), Math.max(Math.abs(r), Math.abs(q + r))) > maxRing) continue;
                cells.add(new int[]{q, r});
            }
        }
        cells.sort(Comparator
                .<int[]>comparingInt(c -> c[0] * c[0] + c[0] * c[1] + c[1] * c[1])
                .thenComparingInt(c -> c[1])
                .thenComparingInt(c -> 2 * c[0] + c[1]));
        if (cells.size() > limit) cells = new ArrayList<>(cells.subList(0, limit));
        return cells;
    }

    // ------------------------------------------------------------------ bounds

    private static BarBounds barBounds(int w, int h) {
        float margin = Math.max(12f, w * 0.015f);
        float barW = Math.max(220f, w * 0.20f);
        float left = w - margin - barW;
        float right = left + barW;
        float alleyW = barW / NUM_ALLEYS;
        float rowH = Math.max(30f, h * (float) OBSTACLE_HEIGHT_FRAC);
        float playerY = h * 0.85f;
        return new BarBounds(left, right, barW, alleyW, rowH, playerY);
    }

    private record BarBounds(float left, float right, float barWidth,
                             float alleyWidth, float rowH, float playerY) {
    }

    // ------------------------------------------------------------------ paint

    /**
     * Renders one frame into {@link #offscreen}, then writes it to the window
     * surface in a single {@code Src} blit. One atomic write per frame is what
     * stops the macOS compositor from tearing the translucent surface; drawing
     * many primitives straight onto the window surface is what used to strobe.
     */
    @Override
    protected void paintComponent(Graphics graphics) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        BufferedImage frame = offscreenFrame(w, h);

        Graphics2D fg = frame.createGraphics();
        try {
            fg.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            fg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            fg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);

            // Start from a fully transparent frame; the C1 road is drawn after.
            fg.setComposite(AlphaComposite.Clear);
            fg.fillRect(0, 0, w, h);
            fg.setComposite(AlphaComposite.SrcOver);

            BarBounds b = barBounds(w, h);

            fg.setColor(Palette.C1);
            fg.fillRect(Math.round(b.left()), 0, Math.round(b.barWidth()), h);

            drawObstacles(fg, b);
            drawEnemyFormation(fg, b);
            drawPlayer(fg, b);
            drawScore(fg, b, h);
            drawDebug(fg, w, h);
            drawEscIndicator(fg, w, h);
        } finally {
            fg.dispose();
        }

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            // Src (not SrcOver): the frame's alpha fully replaces the surface,
            // so old pixels cannot linger between frames.
            g.setComposite(AlphaComposite.Src);
            g.drawImage(frame, 0, 0, null);
        } finally {
            g.dispose();
        }
    }

    private BufferedImage offscreenFrame(int w, int h) {
        if (offscreen != null && offscreen.getWidth() == w && offscreen.getHeight() == h) {
            return offscreen;
        }
        GraphicsConfiguration gc = getGraphicsConfiguration();
        BufferedImage img = null;
        if (gc != null) {
            img = gc.createCompatibleImage(w, h, Transparency.TRANSLUCENT);
        }
        if (img == null) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        offscreen = img;
        return offscreen;
    }

    private void drawObstacles(Graphics2D g2, BarBounds b) {
        float barW = b.alleyWidth() * (float) OBSTACLE_WIDTH_FRAC_OF_ALLEY;
        int h = getHeight();

        for (Obstacle obs : obstacles) {
            float top = (float) obs.y;
            float bot = top + b.rowH();
            if (bot < 0 || top > h) continue;

            float alleyLeft = b.left() + obs.alley * b.alleyWidth();
            float x = alleyLeft + (b.alleyWidth() - barW) / 2f;

            float alpha = obs.consumed ? 0.35f : 1.0f;
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

            g2.setColor(barColor(obs.op));
            g2.fillRect(Math.round(x), Math.round(top), Math.round(barW), Math.round(b.rowH()));

            g2.setColor(Palette.WHITE);
            g2.setFont(Fonts.bold(b.rowH() * 0.55f));
            FontMetrics fm = g2.getFontMetrics();
            float baseline = top + b.rowH() / 2f + (fm.getAscent() - fm.getDescent()) / 2f;
            Draw.centered(g2, obs.text(), x + barW / 2f, baseline);

            g2.setComposite(AlphaComposite.SrcOver);
        }
    }

    /** Subtract / divide bars are red (C3); add / multiply bars are blue (C2). */
    private static Color barColor(char op) {
        return (op == '-' || op == '/') ? Palette.C3 : Palette.C2;
    }

    private void drawPlayer(Graphics2D g2, BarBounds b) {
        long visualPot = currentDisplayPot();
        if (!initialized || visualPot <= 0L) return;

        float r = playerRadius(b);
        float py = playerY;
        float spacing = r * SPACING_FACTOR;
        float strokeW = Math.max(1.5f, r * 0.14f);

        g2.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for (int i = 0; i < SLOT_COUNT; i++) {
            float a = clamp01(slotAlpha[i]);
            if (a <= 0.001f) continue;
            int alpha = Math.round(a * 255f);

            float fx = smoothCx + offsetX(i, spacing);
            float fy = py + offsetY(i, spacing);

            g2.setColor(new Color(Palette.C2.getRed(), Palette.C2.getGreen(),
                    Palette.C2.getBlue(), alpha));
            g2.draw(new Ellipse2D.Float(fx - r, fy - r, r * 2f, r * 2f));

            if (slotCount[i] > 1L) {
                drawStackCount(g2, fx, fy, r, slotCount[i], alpha, Palette.C2);
            }
        }

        g2.setColor(Palette.C2);
        g2.draw(new Ellipse2D.Float(playerCx - r, py - r, r * 2f, r * 2f));
    }

    private void drawEnemyFormation(Graphics2D g2, BarBounds b) {
        long visualEnemy = currentDisplayEnemy();
        if (visualEnemy <= 0L || enemyAlpha <= 0.01f) return;

        float r = playerRadius(b);
        float spacing = r * SPACING_FACTOR;
        float strokeW = Math.max(1.5f, r * 0.14f);
        int alpha = Math.round(clamp01(enemyAlpha) * 255f);
        if (alpha <= 0) return;

        g2.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        long followerCount = visualEnemy - 1L;

        for (int i = 0; i < SLOT_COUNT; i++) {
            long count = countForSlot(i, followerCount);
            if (count <= 0L) continue;
            float fx = enemyCx + offsetX(i, spacing);
            float fy = enemyCy + offsetY(i, spacing);

            g2.setColor(new Color(Palette.C3.getRed(), Palette.C3.getGreen(),
                    Palette.C3.getBlue(), alpha));
            g2.draw(new Ellipse2D.Float(fx - r, fy - r, r * 2f, r * 2f));

            if (count > 1L) {
                drawStackCount(g2, fx, fy, r, count, alpha, Palette.C3);
            }
        }

        g2.setColor(new Color(Palette.C3.getRed(), Palette.C3.getGreen(),
                Palette.C3.getBlue(), alpha));
        g2.draw(new Ellipse2D.Float(enemyCx - r, enemyCy - r, r * 2f, r * 2f));
    }

    private void drawStackCount(Graphics2D g2, float cx, float cy,
                                float r, long count, int alpha, Color base) {
        String s = formatCount(count);
        float fs = Math.min(r * 1.05f, (r * 2.2f) / s.length());
        if (fs < 3f) return;
        g2.setFont(Fonts.bold(fs));
        g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
        FontMetrics fm = g2.getFontMetrics();
        float baseline = cy + (fm.getAscent() - fm.getDescent()) / 2f;
        Draw.centered(g2, s, cx, baseline);
    }

    private static String formatCount(long n) {
        if (n < 1_000L) return Long.toString(n);
        if (n < 10_000L) return String.format("%.1fk", n / 1_000.0);
        if (n < 1_000_000L) return (n / 1_000L) + "k";
        if (n < 10_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n < 1_000_000_000L) return (n / 1_000_000L) + "M";
        if (n < 10_000_000_000L) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n < 1_000_000_000_000L) return (n / 1_000_000_000L) + "B";
        if (n < 10_000_000_000_000L) return String.format("%.1fT", n / 1_000_000_000_000.0);
        if (n < 1_000_000_000_000_000L) return (n / 1_000_000_000_000L) + "T";
        return String.format("%.1fQ", n / 1_000_000_000_000_000.0);
    }

    private static String joinBarText(List<Obstacle> bars) {
        if (bars == null || bars.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bars.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(bars.get(i).text());
        }
        return sb.toString();
    }

    /** White score counter drawn at the top of the road bar. */
    private void drawScore(Graphics2D g2, BarBounds b, int h) {
        String s = Integer.toString(score);
        if (s.isEmpty()) return;

        float fs = Math.max(24f, b.barWidth() * 0.24f);
        float topPad = Math.max(6f, h * 0.012f);
        float cx = (b.left() + b.right()) / 2f;
        float baseline = topPad + fs;

        g2.setFont(Fonts.bold(fs));

        g2.setColor(new Color(0, 0, 0, 140));
        Draw.centered(g2, s, cx + Math.max(1f, fs * 0.03f),
                baseline + Math.max(1f, fs * 0.03f));

        g2.setColor(Palette.WHITE);
        Draw.centered(g2, s, cx, baseline);
    }

    private void drawDebug(Graphics2D g2, int w, int h) {
        if (!debugVisible) return;

        List<String> lines = new ArrayList<>();
        lines.add("DEBUG  [X to hide]");
        lines.add("phase:     " + phase);
        lines.add("score:     " + score);
        lines.add("high:      " + ctx.getHighScore());
        lines.add("pot:       " + pot);
        lines.add("dispPot:   " + currentDisplayPot());
        lines.add("enemy:     " + enemyNumber);
        lines.add("dispEnemy: " + currentDisplayEnemy());
        lines.add("cycle:     " + cycleBars.size() + " / " + barsPerCycle);
        lines.add("startPot:  " + cycleStartPot);
        lines.add("spawnRate: " + String.format("%.2f", spawnRate));
        lines.add("obsSpeed:  " + String.format("%.3f", obstacleSpeedFrac));
        lines.add("obstacles: " + obstacles.size());

        lines.add("");
        lines.add("last enemy calc:");
        if (!debugCalcValid) {
            lines.add("  (no fight yet)");
        } else {
            lines.add("  bars:     " + debugCalcBars);
            lines.add("  startPot: " + debugCalcStartPot);
            lines.add("  highest:  " + debugCalcHighest);
            lines.add("  maxRoll:  1.." + debugCalcMaxRoll);
            lines.add("  roll:     " + debugCalcRoll);
            lines.add("  enemy:    " + debugCalcHighest
                    + " \u2212 " + debugCalcRoll + " = " + debugCalcEnemy);
        }

        lines.add("pos:       " + Math.round(playerCx) + ", " + Math.round(playerY));

        float fs = Math.max(11f, Math.min(w, h) * 0.017f);
        g2.setFont(Fonts.bold(fs));
        FontMetrics fm = g2.getFontMetrics();
        float lineH = fm.getHeight() * 1.02f;
        float pad = Math.max(10f, Math.min(w, h) * 0.012f);
        float textPad = fs * 0.7f;

        float maxW = 0f;
        for (String line : lines) {
            float lw = fm.stringWidth(line);
            if (lw > maxW) maxW = lw;
        }
        float boxW = maxW + textPad * 2f;
        float boxH = lineH * lines.size() + textPad * 2f;
        float boxX = pad;
        float boxY = h - pad - boxH;

        g2.setColor(new Color(Palette.C1.getRed(), Palette.C1.getGreen(),
                Palette.C1.getBlue(), 210));
        g2.fillRoundRect(Math.round(boxX), Math.round(boxY),
                Math.round(boxW), Math.round(boxH), 8, 8);

        g2.setColor(new Color(Palette.LIGHT_GRAY.getRed(), Palette.LIGHT_GRAY.getGreen(),
                Palette.LIGHT_GRAY.getBlue(), 110));
        g2.setStroke(new BasicStroke(1f));
        g2.drawRoundRect(Math.round(boxX), Math.round(boxY),
                Math.round(boxW), Math.round(boxH), 8, 8);

        g2.setColor(Palette.LIGHT_GRAY);
        float x = boxX + textPad;
        for (int i = 0; i < lines.size(); i++) {
            float baseline = boxY + textPad + fm.getAscent() + i * lineH;
            g2.drawString(lines.get(i), x, baseline);
        }
    }

    private void drawEscIndicator(Graphics2D g2, int w, int h) {
        if (escAmount <= 0.0) return;

        float amount = (float) Math.min(1.0, Math.max(0.0, escAmount));
        int alpha = Math.round(255 * amount);
        if (alpha <= 0) return;

        float minDim = Math.min(w, h);
        float outerR = clamp(minDim * 0.065f, 42f, 78f);
        float pad = Math.max(16f, minDim * 0.035f);
        float cx = pad + outerR;
        float cy = pad + outerR;

        float ringR = outerR * 0.78f;
        float strokeW = Math.max(3f, outerR * 0.12f);

        g2.setColor(new Color(Palette.C1.getRed(), Palette.C1.getGreen(),
                Palette.C1.getBlue(), alpha));
        g2.fill(new Ellipse2D.Float(cx - outerR, cy - outerR, outerR * 2f, outerR * 2f));

        g2.setColor(new Color(Palette.C2.getRed(), Palette.C2.getGreen(),
                Palette.C2.getBlue(), Math.round(alpha * 0.30f)));
        g2.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new Ellipse2D.Float(cx - ringR, cy - ringR, ringR * 2f, ringR * 2f));

        g2.setColor(new Color(Palette.C2.getRed(), Palette.C2.getGreen(),
                Palette.C2.getBlue(), alpha));
        g2.draw(new Arc2D.Float(cx - ringR, cy - ringR, ringR * 2f, ringR * 2f,
                90f, -360f * amount, Arc2D.OPEN));

        g2.setColor(new Color(Palette.C2.getRed(), Palette.C2.getGreen(),
                Palette.C2.getBlue(), alpha));
        g2.setFont(Fonts.bold(ringR * 0.62f));
        FontMetrics fm = g2.getFontMetrics();
        float baseline = cy + (fm.getAscent() - fm.getDescent()) / 2f;
        Draw.centered(g2, "ESC", cx, baseline);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ------------------------------------------------------------------ inner types

    private static final class Obstacle {
        final int alley;
        final char op;
        final int value;
        final int group;
        double y;
        boolean consumed;

        Obstacle(int alley, char op, int value, int group) {
            this.alley = alley;
            this.op = op;
            this.value = value;
            this.group = group;
        }

        String text() {
            char display = switch (op) {
                case '*' -> '\u00D7';
                case '/' -> '\u00F7';
                default -> op;
            };
            return display + " " + value;
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void addNotify() {
        super.addNotify();

        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null && !windowFocusHooked) {
            windowFocusHooked = true;
            window.addWindowFocusListener(new WindowFocusListener() {
                @Override public void windowGainedFocus(WindowEvent e) { }
                @Override public void windowLostFocus(WindowEvent e) {
                    heldKeys.clear();
                    xHeld = false;
                }
            });
        }

        lastTickNanos = System.nanoTime();
        frameTimer.start();
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    @Override
    public void removeNotify() {
        frameTimer.stop();
        heldKeys.clear();
        xHeld = false;
        super.removeNotify();
    }
}