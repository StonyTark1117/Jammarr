package stonytark.pampmod.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import stonytark.pampmod.Pampmod;
import stonytark.pampmod.server.PlaybackTimeline;
import stonytark.pampmod.server.SlidingWindowRateLimiter;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@GameTestHolder(Pampmod.MODID)
@PrefixGameTestTemplate(false)
public final class PampGameTests {
    @GameTest(template = "empty", timeoutTicks = 20)
    public static void serverLoadsAuthoritativeTimingAndRateLimitCode(GameTestHelper helper) {
        AtomicLong clock = new AtomicLong(1_000); PlaybackTimeline timeline = new PlaybackTimeline(clock::get);
        timeline.schedule(10_000, 0, false, 5_000); clock.set(7_000);
        helper.assertTrue(timeline.positionMs() == 1_000, "Authoritative server timeline did not advance from its scheduled start");
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(); UUID player = UUID.randomUUID();
        helper.assertTrue(limiter.allow(player, 1, 1_000) && !limiter.allow(player, 1, 1_100), "Server request rate limit was not enforced");
        helper.succeed();
    }
    private PampGameTests() {}
}
