package pl.kosma.geodesy;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.WrittenBookContentComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.RawFilteredPair;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class UnholyBookOfGeodesy {

    static public ItemStack summonKrivbeknih() {
        ItemStack grimoire = Items.WRITTEN_BOOK.getDefaultStack();
        List<String> pages = new ArrayList<>();
        pages.add(
                """
                             *
                   The Unholy Book
                     of Geodesy
                             *
                  
                      by Kosmolot
                
                
                TL;DR for heathens:
                /geodesy area
                /geodesy analyze
                /geodesy project
                ...place blocks...
                /geodesy assemble
                """);
        pages.add(
                """
                Step 1:
                Scout out a nice, healthy geode.
                
                Paradoxically, a big geode is more difficult to farm than a small one.
                
                Curb your greed and you will be rewarded.
                """);
        pages.add(
                """
                Step 2:
                /geodesy area <xyz> <xyz>

                Mark the corners of the area that contains a geode. You don't have to be exact.
                
                Spectator Mode and Tab completion are your friends.
                """);
        pages.add(
                """
                Step 3:
                /geodesy analyze

                For smaller geodes you don't need all three projections to get high efficiency.
                
                Less effort, same reward!
                """);
        pages.add(
                """
                Step 4:
                /geodesy project <directions>

                For your convenience, you can adjust those without changing the efficiency of the farm:
                
                - Swap north/soth
                - Swap east/west
                - Swap up/down
                - Change order of directions
                """);
        pages.add(
                """
                Step 5:
                
                Place sticky block structures on the sides of the farm. All moss blocks and no crying obsidian blocks should be covered.
                
                It's a bit like sudoku.
                """);
        pages.add(
                """
                Step 6:
                
                Place mob heads as markers indicating where the flying machines should go. See the Curseforge mod page for details.
                
                Just don't summon a wither!
                """);
        pages.add(
                """
                Step 7:
                /geodesy assemble
                
                Once you are satisfied with the layout, assemble the farm and see the flying machines in their full glory.
                
                The Beast is Ready.
                """);
        pages.add(
                """
                Step 8:
                
                Now the boring part: for trigger wiring, collection system, etc. Follow ilmango's video.
                
                I won't hold your hand.
                
                
                
                ...unless?
                """);
        pages.add(
                """
                Step 9:
                
                There is no step 9.
                
                
                
                
                Feeling lost?
                
                Check out the mod page on Curseforge for screenshots and more detailed instructions.
                """);
        grimoire.set(DataComponentTypes.WRITTEN_BOOK_CONTENT, new WrittenBookContentComponent(RawFilteredPair.of("The Unholy Book of Geodesy"), "Kosmolot", 0, pages.stream().map(Text::of).map(RawFilteredPair::of).toList(), true));
        return grimoire;
    }
}
