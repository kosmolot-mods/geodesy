# TODO

There are still so many things that can be improved about the mod.
Feel free to tackle any of the things below.

## Smaller flying machines

- It's possible to harvest 1x2 areas using the flying machines that ilmango
  has shown in [Scicraft S02E04](https://www.youtube.com/watch?v=05AEd_1KQNY).
- It's possible to harvest 1x3 areas by skipping the front extension and using
  the engine directly to punch out the blocks.
- It also *might* be possible to poke out shards in 1x1 holes using a long "pole"
  of slime blocks, but this depends on how deep the shards are.

The scope of this task is:

1. Adding a function that generates those,
2. Adding a new type of markers blocks,
3. Documentation for the above.

## Improved projection logic

The current projection logic doesn't understand that a 1x1 area can't be harvested.
The projection logic should be extended to detect those areas and not mark them
as "already harvested" (so other projection directions can still try to harvest them).

Alternatively, if smaller flying machines are implemented, the current logic can stay.

## Better calculation of actual efficiency

The efficiency calculation does not consider the actual placement (or lack) of
the flying machines - just the fact that the cluster can be projected to a side
wall without colliding with a budding block. This also ties with the improved
support for more types of flying machines.

## Increasing efficiency by breaking blocks

This one is tied to the projection logic as well. In some cases, removing blocks
actually increases efficiency.

## Relaxation of blocker block location requirements

Currently the mod expects an L-shaped layout when marking the flying machines;
this is unneccessary. The only true requirement is a 1x3 area for the machine
and a single blocker block anywhere on the same sticky block cluster.

## Automatic sticky block clustering

I am aware that this problem is NP-complete but at this size it can hopefully
be either bruteforced or at least solved with some dynamic programming.
Algorithmic geniuses and their PRs are welcome.

Several approaches have been suggested so far, including an SAT solver.

## Decoupling from net.minecraft.world.World

The current implementation uses `world.setBlockState` as a sort of working memory.
This should be completely eliminated, so that the logic of designing a geode farm
is completely decoupled from the logic of pasting the geode farm in the world.
This one will be tricky but hopefully worth it. It should also hopefully increase
the code quality and perhaps open doors for Forge support. (Not that I like Forge.)

## Litematica integration

Ideally, the mod should generate a schematic in the current world - that way,
the player just needs to mark the geode (maybe with autodetection even?) and
the schematic of a farm would be immediately plopped in place.

## Better water collection system generation

The current code struggles with weird sizes like 9x29. I write it while having a nasty flu,
and I couldn't be bothered to do things properly. I am 100% sure it can be done better.
The problem is most visible when doing farms that encompass two geodes at the same time
(which is a bad idea due to extra collisions), so I don't consider this super big priority.

## Trigger wiring

It still requires some manual work, and I'm 100% sure it can be fully automated to
the point where the wiring requires zero extra manual work. This includes a few things:

## Automated testing

We really should have a set of test data, especially for the extreme cases.
Fabric-Carpet's `plop()` command is very useful for artificially generating geodes.
Having the test data in a standardized format would also make prototyping new
algorithms much easier.

## Better error reporting

Some user errors cause an exception to be thrown; those should be handled in a more
graceful way.
