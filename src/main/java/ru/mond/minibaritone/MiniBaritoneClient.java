package ru.mond.minibaritone;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class MiniBaritoneClient implements ClientModInitializer {
    public static final String MOD_ID = "minibaritone";
    private static final PathBot BOT = new PathBot();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(BOT::tick);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("mb")
                    .executes(ctx -> help(ctx.getSource()))
                    .then(literal("goto")
                            .then(argument("x", IntegerArgumentType.integer())
                                    .then(argument("y", IntegerArgumentType.integer())
                                            .then(argument("z", IntegerArgumentType.integer())
                                                    .executes(ctx -> {
                                                        int x = IntegerArgumentType.getInteger(ctx, "x");
                                                        int y = IntegerArgumentType.getInteger(ctx, "y");
                                                        int z = IntegerArgumentType.getInteger(ctx, "z");
                                                        BOT.gotoBlock(new BlockPos(x, y, z));
                                                        ctx.getSource().sendFeedback(Text.literal("MiniBaritone: иду к " + x + " " + y + " " + z));
                                                        return 1;
                                                    })))))
                    .then(literal("stop")
                            .executes(ctx -> {
                                BOT.stop();
                                ctx.getSource().sendFeedback(Text.literal("MiniBaritone: остановлен"));
                                return 1;
                            }))
                    .then(literal("breakfront")
                            .executes(ctx -> {
                                boolean result = BOT.breakFrontBlock();
                                ctx.getSource().sendFeedback(Text.literal(result
                                        ? "MiniBaritone: ломаю блок перед собой"
                                        : "MiniBaritone: перед тобой нет ломаемого блока"));
                                return result ? 1 : 0;
                            }))
                    .then(literal("tree")
                            .then(argument("radius", IntegerArgumentType.integer(1, 100))
                                    .executes(ctx -> {
                                        int radius = IntegerArgumentType.getInteger(ctx, "radius");
                                        BOT.mineTrees(radius);
                                        ctx.getSource().sendFeedback(Text.literal("MiniBaritone: ищу дерево в радиусе " + radius));
                                        return 1;
                                    })))
                    .then(literal("settings")
                            .then(literal("allowBreak")
                                    .then(argument("value", BoolArgumentType.bool())
                                            .executes(ctx -> {
                                                boolean value = BoolArgumentType.getBool(ctx, "value");
                                                BOT.settings().allowBreak = value;
                                                ctx.getSource().sendFeedback(Text.literal("MiniBaritone: allowBreak = " + value));
                                                return 1;
                                            })))
                            .then(literal("allowPlace")
                                    .then(argument("value", BoolArgumentType.bool())
                                            .executes(ctx -> {
                                                boolean value = BoolArgumentType.getBool(ctx, "value");
                                                BOT.settings().allowPlace = value;
                                                ctx.getSource().sendFeedback(Text.literal("MiniBaritone: allowPlace = " + value));
                                                return 1;
                                            })))));

            dispatcher.register(literal("minibaritone")
                    .redirect(dispatcher.getRoot().getChild("mb")));
        });
    }

    private static int help(FabricClientCommandSource source) {
        source.sendFeedback(Text.literal("MiniBaritone команды:"));
        source.sendFeedback(Text.literal("/mb goto <x> <y> <z> — идти к координатам"));
        source.sendFeedback(Text.literal("/mb stop — остановить"));
        source.sendFeedback(Text.literal("/mb breakfront — сломать блок перед собой"));
        source.sendFeedback(Text.literal("/mb tree <1..100> — добывать ближайшие брёвна"));
        source.sendFeedback(Text.literal("/mb settings allowBreak true|false"));
        source.sendFeedback(Text.literal("/mb settings allowPlace true|false"));
        return 1;
    }
}
