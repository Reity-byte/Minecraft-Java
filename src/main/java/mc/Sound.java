package mc;

import java.util.Locale;
import java.util.Random;

/**
 * Všechny zvuky hry: co hraje (druh a materiál) a jak se jmenuje soubor,
 * kterým se dá nahradit.
 *
 * ---------------------------------------------------------------------------
 * Zvuk je DRUH x MATERIÁL. Druh (krok, rozbití, položení, kliknutí) určuje,
 * jak dlouhý a hlasitý zvuk je, jak moc se mu mění výška a jak často smí
 * zaznít; materiál určuje barvu (hlína šustí, kámen cvakne, dřevo zaduní).
 * Materiál bloku se bere ze stejného rozdělení jako tvrdost - viz Material.of().
 * ---------------------------------------------------------------------------
 *
 * Nesahá na OpenAL, takže jde celé otestovat headless.
 */
public enum Sound {

    STEP_EARTH(Kind.STEP, Material.EARTH),
    STEP_WOOD(Kind.STEP, Material.WOOD),
    STEP_STONE(Kind.STEP, Material.STONE),
    STEP_PLANT(Kind.STEP, Material.PLANT),

    BREAK_EARTH(Kind.BREAK, Material.EARTH),
    BREAK_WOOD(Kind.BREAK, Material.WOOD),
    BREAK_STONE(Kind.BREAK, Material.STONE),
    BREAK_PLANT(Kind.BREAK, Material.PLANT),

    PLACE_EARTH(Kind.PLACE, Material.EARTH),
    PLACE_WOOD(Kind.PLACE, Material.WOOD),
    PLACE_STONE(Kind.PLACE, Material.STONE),
    PLACE_PLANT(Kind.PLACE, Material.PLANT),

    /** Kliknutí na tlačítko v menu. Nemá materiál. */
    CLICK(Kind.CLICK, null);

    /**
     * Druh zvuku a pravidla, podle kterých se přehrává.
     *
     * ⚠️ Cooldown je na DRUH, ne na jednotlivý zvuk. Rychlé kopání střídavě
     * hlíny a listí by jinak prošlo, protože každý z nich má svůj zvuk - a
     * "kulomet" je problém druhu, ne materiálu.
     *
     * Obměna výšky je ± podíl: 0,08 znamená výšku 0,92 až 1,08. Stačí to,
     * aby dva kroky za sebou nezněly jako jedna nahrávka puštěná dvakrát.
     * Kliknutí se mění jen málo - v UI má znít pořád stejně.
     */
    public enum Kind {
        //    cooldown (s)  obměna výšky  hlasitost
        STEP (0.15f,        0.08f,        0.35f),
        BREAK(0.10f,        0.10f,        1.00f),
        PLACE(0.10f,        0.10f,        0.85f),
        CLICK(0.05f,        0.03f,        0.60f);

        public final float cooldown;
        public final float pitchVariation;
        public final float gain;

        Kind(float cooldown, float pitchVariation, float gain)
        {
            this.cooldown = cooldown;
            this.pitchVariation = pitchVariation;
            this.gain = gain;
        }

        /** Náhodná výška tónu kolem 1, rovnoměrně v 1 ± pitchVariation. */
        public float pitch(Random random)
        {
            return 1f + (random.nextFloat() * 2f - 1f) * pitchVariation;
        }
    }

    /** Barva zvuku bloku. */
    public enum Material {
        EARTH, WOOD, STONE, PLANT;

        /**
         * Materiál bloku, nebo null pro bloky, které nezní (vzduch, voda).
         *
         * ⚠️ Rozdělení je STEJNÉ jako skupiny ve World.hardness(): co má
         * stejnou tvrdost, zní stejně. Jen rudy mají tvrdost vlastní a zní
         * jako kámen, a pochodeň jako dřevo, z něhož je. SoundTest hlídá,
         * že se obě tabulky nerozejdou, když přibude nový blok.
         */
        public static Material of(byte block)
        {
            // Blok z labu má jen tvrdost, takže zní podle ní - stejné skupiny.
            BlockDef custom = BlockRegistry.lookup(block);

            if(custom != null)
            {
                return byHardness(custom.hardness());
            }

            return switch(block)
            {
                case World.AIR, World.WATER -> null;
                case World.LEAVES -> PLANT;
                case World.GRASS, World.DIRT, World.SAND -> EARTH;
                case World.PLANKS, World.LOG, World.FENCE, World.CRAFTING_TABLE, World.TORCH -> WOOD;
                case World.STONE, World.STONE_BRICKS, World.COAL_ORE, World.IRON_ORE -> STONE;
                // Stejně jako tvrdost: neznámý blok se chová jako hlína.
                default -> EARTH;
            };
        }

        /**
         * Materiál podle tvrdosti, pro bloky, které nic jiného nemají (bloky
         * z labu). Hranice leží mezi tvrdostmi skupin vestavěných bloků:
         * listí 0,2 | hlína 0,5 | dřevo 0,8 | kámen 1,8 a víc. SoundTest hlídá,
         * že vestavěné bloky (kromě pochodně a rud) vyjdou stejně jako v of().
         */
        public static Material byHardness(float hardness)
        {
            if(hardness < 0.35f) return PLANT;
            if(hardness < 0.65f) return EARTH;
            if(hardness < 1.3f)  return WOOD;
            return STONE;
        }
    }

    public final Kind kind;
    public final Material material;

    Sound(Kind kind, Material material)
    {
        this.kind = kind;
        this.material = material;
    }

    /** Jméno souboru, kterým se zvuk nahradí: sounds/<jméno>.wav. */
    public String fileName()
    {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Sound stepOf(byte block)  { return of(Kind.STEP, block); }
    public static Sound breakOf(byte block) { return of(Kind.BREAK, block); }
    public static Sound placeOf(byte block) { return of(Kind.PLACE, block); }

    /** Zvuk daného druhu pro materiál bloku, nebo null, když blok nezní. */
    private static Sound of(Kind kind, byte block)
    {
        Material material = Material.of(block);

        if(material == null)
        {
            return null;
        }

        for(Sound sound : values())
        {
            if(sound.kind == kind && sound.material == material)
            {
                return sound;
            }
        }

        return null;
    }
}
