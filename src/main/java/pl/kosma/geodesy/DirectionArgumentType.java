package pl.kosma.geodesy;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.arguments.StringRepresentableArgument;
import net.minecraft.core.Direction;

public class DirectionArgumentType extends StringRepresentableArgument<Direction> {

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
