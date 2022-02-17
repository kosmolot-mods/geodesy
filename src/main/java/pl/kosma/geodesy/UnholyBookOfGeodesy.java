package pl.kosma.geodesy;

import com.google.gson.Gson;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;

public class UnholyBookOfGeodesy {

    static public ItemStack summonKrivbeknih() {
        ItemStack grimoire = Items.WRITTEN_BOOK.getDefaultStack();
        NbtList pages = new NbtList();
        pages.add(pages.size(), NBTJsonString(
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
                """));
        pages.add(pages.size(), NBTJsonString(
                """
                Step 1: Scout out a nice, healthy geode.
                
                Paradoxically, a big geode is more difficult to farm than a small one.
                
                Curb your greed and you will be rewarded. 
                """));
        pages.add(pages.size(), NBTJsonString(
                """
                Step 2:
                /geodesy area <xyz> <xyz> 

                Mark the corners of the area that contains a geode. You don't have to be exact.               
                
                Spectator Mode and Tab completion are your friends.
                """));
        pages.add(pages.size(), NBTJsonString(
                """
                Step 3:
                /geodesy analyze

                For smaller geodes you don't need all three projections to get high efficiency.
                
                Less effort, same reward!
                """));
        pages.add(pages.size(), NBTJsonString(
                """
                Step 4:
                /geodesy project <directions>

                For your convenience, you can adjust those without changing the efficiency of the farm:
                
                - Swap north/soth
                - Swap east/west 
                - Swap up/down
                - Change order of directions
                """));
        pages.add(pages.size(), NBTJsonString(
                """
                Step 5:
                
                Place the sticky block structures and flying machine markers, as shown on screenshots.
                
                Can't describe this in a book very well. Sorry. 
                """));
        pages.add(pages.size(), NBTJsonString(
                """
                Step 6:
                /geodesy assemble
                
                The Beast is Ready.
                
                Minor details such as trigger writing, redstone clock and collection system are for you to figure out.  
                """));
        pages.add(pages.size(), NBTJsonString(
                """
                Feeling lost?
                
                Check out the mod page on Curseforge for screenshots and more detailed instructions.
                """));
        grimoire.setSubNbt("author", NbtString.of("Kosmolot"));
        grimoire.setSubNbt("title", NbtString.of("The Unholy Book of Geodesy"));
        grimoire.setSubNbt("pages", pages);
        return grimoire;
    }

    static private NbtString NBTJsonString(String text) {
        return NbtString.of(new Gson().toJson(text));
    }
}
