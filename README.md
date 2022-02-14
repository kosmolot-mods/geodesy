# Geodesy

Small Fabric mod for calculating amethyst farm flying machine layout.
Inspired by [ilmango's video](https://www.youtube.com/watch?v=fY90xF3ug84) and
the flying machine based farm made by neffty87.

## Usage

You should only run this in disposable worlds, either in single player or on servers
where you have op. The mod *will* destroy the immediate surroundings of the geode!

1. Run `/geodesy prepare <first corner> <second corner>` to clear out the surrounding
area of any junk and calculate the geode area. The area will be highlighted for you
to verify you've entered correct coordinates.

You don't have to be exact with the corner coordinates - you can select a larger
volume and the mod will find the geode anyway. The only time you have to be careful
is when you have two geodes very close to each other (this is also the reason we don't
have autodetection - it easily got confused with multiple geodes nearby).

2. Run `/geodesy build <axes>` to create the obsidian structure:

* Moss block means this location contains amethyst clusters to be pushed and destroyed.
* Crying obsidian block means this location contains budding amethyst blocks, so
  the flying machine must not fly through that location.
* Obsidian marks the outer frame of the machine.

3. Place the slime and honey blocks outside the structure, as shown by ilmango.

4. Run `/geodesy collapse` to "push" the slime and honey blocks inside the obsidian frame.

5. Build the flying machines, as shown by ilmango.

![screenshot](screenshot.png)
