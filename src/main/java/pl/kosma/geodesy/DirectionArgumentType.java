package pl.kosma.geodesy;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.argument.EnumArgumentType;
import net.minecraft.util.math.Direction;

public class DirectionArgumentType extends EnumArgumentType<Direction> {

    private DirectionArgumentType() {
        super(Direction.CODEC, Direction::values);
    }

    public static DirectionArgumentType direction() {
        return new DirectionArgumentType();
    }

    public static Direction getDirection(CommandContext<?> context, String id) {
        return context.getArgument(id, Direction.class);
    }
}
