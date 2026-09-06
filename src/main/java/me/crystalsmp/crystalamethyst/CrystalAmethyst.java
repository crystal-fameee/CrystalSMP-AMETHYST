package me.crystalsmp.crystalamethyst;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.AmethystCluster;
import org.bukkit.command.*;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class CrystalAmethyst extends JavaPlugin implements Listener {
    private Economy economy;
    private Object playerPointsApi;
    private NamespacedKey typeKey, specialKey, displayKey;
    private final Map<String, Growth> growing = new HashMap<>();
    private final Map<String, UUID> specialDisplays = new HashMap<>();
    private final Set<String> generatedGeodes = new HashSet<>();

    private record Growth(long requiredMillis, long progressMillis, long lastUpdate) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        typeKey = new NamespacedKey(this, "item_type");
        specialKey = new NamespacedKey(this, "special_amethyst"); displayKey = new NamespacedKey(this, "special_display");
        setupVault(); setupPlayerPoints(); registerRecipes();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("crystalamethyst")).setExecutor(this::command);
        getServer().getScheduler().runTaskTimer(this, this::tickGrowth, 20L, 20L);
    }
    private void setupVault(){ RegisteredServiceProvider<Economy> r=getServer().getServicesManager().getRegistration(Economy.class); if(r!=null)economy=r.getProvider(); }
    private void setupPlayerPoints(){ var p=getServer().getPluginManager().getPlugin("PlayerPoints"); if(p!=null)try{playerPointsApi=p.getClass().getMethod("getAPI").invoke(p);}catch(Exception ignored){} }

    private ItemStack item(String type,String name,Material mat){ ItemStack i=new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.setDisplayName(ChatColor.translateAlternateColorCodes('&',name)); m.getPersistentDataContainer().set(typeKey,PersistentDataType.STRING,type); i.setItemMeta(m); return i; }
    private String type(ItemStack i){ if(i==null||!i.hasItemMeta())return null; return i.getItemMeta().getPersistentDataContainer().get(typeKey,PersistentDataType.STRING); }
    private ItemStack seed(){return item("seed","&d&lАметистовое семя",Material.AMETHYST_SHARD);}
    private ItemStack special() {
        ItemStack i = item("amethyst", "&5&l✦ Особый аметист", Material.AMETHYST_CLUSTER);
        ItemMeta m = i.getItemMeta();
        m.setLore(List.of(ChatColor.GRAY + "Редкий аметист из природной геоды",
                ChatColor.DARK_PURPLE + "ПКМ → Аметистовое семя"));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        i.setItemMeta(m);
        return i;
    }
    private ItemStack crystal(){return item("crystal","&d&lКристальная монета",Material.ECHO_SHARD);}
    private ItemStack amCoin(){return item("amethystcoin","&5&lАметистовая монета",Material.ECHO_SHARD);}
    private String key(Block b){return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}

    @EventHandler(ignoreCancelled = true)
    public void onChunk(ChunkLoadEvent e) {
        if (!e.isNewChunk()) return;

        Chunk c = e.getChunk();
        // Wait one tick so vanilla terrain/features (including amethyst geodes)
        // are fully available before we inspect and modify the chunk.
        getServer().getScheduler().runTask(this, () -> processNewChunk(c));
    }

    private void processNewChunk(Chunk c) {
        World w = c.getWorld();
        int minX = c.getX() * 16;
        int minZ = c.getZ() * 16;

        for (int x = minX; x < minX + 16; x++) {
            for (int y = w.getMinHeight(); y < w.getMaxHeight(); y++) {
                for (int z = minZ; z < minZ + 16; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    if (b.getType() != Material.BUDDING_AMETHYST) continue;

                    String geode = geodeKey(b);
                    if (generatedGeodes.add(geode)) {
                        trySpawnSpecial(b);
                    }
                }
            }
        }
    }

    private String geodeKey(Block b) {
        // Find the minimum XYZ of the connected amethyst shell.
        // This gives one stable identifier for one natural geode even if it crosses chunks.
        Set<String> visited = new HashSet<>();
        ArrayDeque<Block> queue = new ArrayDeque<>();
        queue.add(b);

        int minX = b.getX(), minY = b.getY(), minZ = b.getZ();
        int maxX = b.getX(), maxY = b.getY(), maxZ = b.getZ();

        while (!queue.isEmpty() && visited.size() < 5000) {
            Block cur = queue.poll();
            String k = key(cur);
            if (!visited.add(k)) continue;

            minX = Math.min(minX, cur.getX());
            minY = Math.min(minY, cur.getY());
            minZ = Math.min(minZ, cur.getZ());
            maxX = Math.max(maxX, cur.getX());
            maxY = Math.max(maxY, cur.getY());
            maxZ = Math.max(maxZ, cur.getZ());

            for (BlockFace face : new BlockFace[]{
                    BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                    BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
            }) {
                Block next = cur.getRelative(face);
                if (isGeodeAmethyst(next.getType()) && !visited.contains(key(next))) {
                    queue.add(next);
                }
            }
        }

        return b.getWorld().getUID() + ":" + minX + ":" + minY + ":" + minZ
                + ":" + maxX + ":" + maxY + ":" + maxZ;
    }

    private boolean isGeodeAmethyst(Material m) {
        return m == Material.BUDDING_AMETHYST
                || m == Material.AMETHYST_BLOCK
                || m == Material.SMALL_AMETHYST_BUD
                || m == Material.MEDIUM_AMETHYST_BUD
                || m == Material.LARGE_AMETHYST_BUD
                || m == Material.AMETHYST_CLUSTER;
    }

    private void trySpawnSpecial(Block core){
        if(ThreadLocalRandom.current().nextDouble()>getConfig().getDouble("special-amethyst-geode-chance",0.5))return;
        List<Block> candidates=new ArrayList<>(); int r=8;
        for(int x=core.getX()-r;x<=core.getX()+r;x++) for(int y=Math.max(core.getWorld().getMinHeight(),core.getY()-r);y<=Math.min(core.getWorld().getMaxHeight()-1,core.getY()+r);y++) for(int z=core.getZ()-r;z<=core.getZ()+r;z++){
            Block b=core.getWorld().getBlockAt(x,y,z); Material m=b.getType();
            if((m==Material.AMETHYST_CLUSTER||m==Material.LARGE_AMETHYST_BUD||m==Material.MEDIUM_AMETHYST_BUD||m==Material.SMALL_AMETHYST_BUD) && !b.hasMetadata("crystal_amethyst_special")) candidates.add(b);
        }
        if(candidates.isEmpty())return;
        Block target=candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        target.setType(Material.AMETHYST_CLUSTER,false);
        markSpecial(target);
    }
    private void markSpecial(Block b){
        String k=key(b); specialDisplays.remove(k); b.setMetadata("crystal_amethyst_special",new org.bukkit.metadata.FixedMetadataValue(this,true));
        BlockDisplay d=b.getWorld().spawn(b.getLocation().add(0.5,0.5,0.5),BlockDisplay.class,ent->{
            BlockData data=Bukkit.createBlockData(Material.AMETHYST_CLUSTER); AmethystCluster a=(AmethystCluster)data; a.setFacing(org.bukkit.block.BlockFace.UP); a.setWaterlogged(false); ent.setBlock(data); ent.setTransformation(new Transformation(new Vector3f(-0.175f,-0.175f,-0.175f),new org.joml.Quaternionf(),new Vector3f((float)getConfig().getDouble("special-amethyst-display-scale",1.35),(float)getConfig().getDouble("special-amethyst-display-scale",1.35),(float)getConfig().getDouble("special-amethyst-display-scale",1.35)),new org.joml.Quaternionf())); ent.setGlowing(true); ent.getPersistentDataContainer().set(displayKey, PersistentDataType.BYTE, (byte)1); ent.setPersistent(true);
        }); specialDisplays.put(k,d.getUniqueId());
    }

    private boolean isSpecialBlock(Block b) {
        if (b.hasMetadata("crystal_amethyst_special")) return true;
        for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().add(.5, .5, .5), 1.5, 1.5, 1.5)) {
            if (en instanceof BlockDisplay &&
                    en.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled=true)
    public void onBreak(BlockBreakEvent e){
        Block b=e.getBlock(); String k=key(b);
        if(growing.containsKey(k)){e.setCancelled(true);e.getPlayer().sendMessage(ChatColor.RED+"Этот аметист ещё растёт.");return;}
        if (isSpecialBlock(b)) {
            e.setDropItems(false);
            removeDisplay(b);
            b.removeMetadata("crystal_amethyst_special", this);
            b.getWorld().dropItemNaturally(b.getLocation(), special());
            return;
        }
    }
    @EventHandler public void onExplode(EntityExplodeEvent e) {
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (isSpecialBlock(b)) {
                removeDisplay(b);
                b.removeMetadata("crystal_amethyst_special", this);
                it.remove();
            }
        }
    }
    private void removeDisplay(Block b) {
        UUID id = specialDisplays.remove(key(b));
        if (id != null) {
            Entity en = Bukkit.getEntity(id);
            if (en != null) en.remove();
            return;
        }
        // The map is in memory only, so after a restart find the persistent
        // BlockDisplay near the special block.
        for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().add(.5, .5, .5), 1.5, 1.5, 1.5)) {
            if (en instanceof BlockDisplay &&
                    en.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)) {
                en.remove();
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSeedPlant(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (!"seed".equals(type(item))) return;

        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        if (clicked == null) return;

        if (clicked.getType() != Material.AMETHYST_BLOCK) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Аметистовое семя можно посадить только на аметистовый блок!");
            return;
        }

        if (e.getBlockFace() != BlockFace.UP) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Семя нужно сажать на верхнюю сторону аметистового блока!");
            return;
        }

        Block planted = clicked.getRelative(BlockFace.UP);
        if (!planted.getType().isAir()) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Над аметистовым блоком должно быть свободное место!");
            return;
        }

        // The seed is consumed exactly once: after successful planting.
        e.setCancelled(true);
        planted.setType(Material.BUDDING_AMETHYST, false);
        long base = getConfig().getLong("growth-seconds", 3600) * 1000L;
        growing.put(key(planted), new Growth(base, 0, System.currentTimeMillis()));
        consumeOne(p, item);
        p.sendMessage(msg("planted"));
        p.playSound(planted.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_PLACE, 1f, 1f);
    }
    private void tickGrowth(){
        long now=System.currentTimeMillis(); Iterator<Map.Entry<String,Growth>> it=growing.entrySet().iterator();
        while(it.hasNext()){
            var en=it.next(); Block b=findBlock(en.getKey()); if(b==null||b.getType()!=Material.BUDDING_AMETHYST){it.remove();continue;}
            Growth g=en.getValue(); long delta=now-g.lastUpdate(); double mult=growthMultiplier(b); long progress=g.progressMillis()+(long)(delta*mult);
            if(progress>=g.requiredMillis()){
                b.setType(Material.AMETHYST_CLUSTER,false); markSpecial(b); it.remove(); b.getWorld().spawnParticle(Particle.END_ROD,b.getLocation().add(.5,.5,.5),20,.35,.35,.35,.02); b.getWorld().playSound(b.getLocation(),Sound.BLOCK_AMETHYST_BLOCK_CHIME,1,1.2f); 
            } else en.setValue(new Growth(g.requiredMillis(),progress,now));
        }
    }
    private double growthMultiplier(Block b){
        double m=1.0; World w=b.getWorld(); int light=w.getBlockAt(b.getX(),b.getY(),b.getZ()).getLightLevel();
        if(light<=7)m*=getConfig().getDouble("dark-growth-multiplier",1.25);
        long time=w.getTime(); boolean night=time>=13000&&time<23000;
        if(night)m*=getConfig().getDouble("night-growth-multiplier",1.25);
        return Math.min(m,1.5);
    }
    private Block findBlock(String k){String[] p=k.split(":",4); if(p.length!=4)return null; try{return Bukkit.getWorld(UUID.fromString(p[0])).getBlockAt(Integer.parseInt(p[1]),Integer.parseInt(p[2]),Integer.parseInt(p[3]));}catch(Exception x){return null;}}

    @EventHandler(ignoreCancelled=true) public void onInteract(PlayerInteractEvent e){
        if(e.getHand()!=EquipmentSlot.HAND||e.getAction()!=Action.RIGHT_CLICK_AIR&&e.getAction()!=Action.RIGHT_CLICK_BLOCK)return; ItemStack i=e.getItem(); String t=type(i); Player p=e.getPlayer();
        if ("amethyst".equals(t)) {
            // Collecting the grown special amethyst gives exactly one amethyst item.
            // A separate right-click converts that item into one seed.
            consumeOne(p, i);
            HashMap<Integer, ItemStack> left = p.getInventory().addItem(seed());
            left.values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
            return;
        }
        if("crystal".equals(t)){if(economy==null){p.sendMessage(msg("no-vault"));return;}double a=ThreadLocalRandom.current().nextInt(getConfig().getInt("crystal-coin-min",1),getConfig().getInt("crystal-coin-max",50)+1);if(economy.depositPlayer(p,a).transactionSuccess()){consumeOne(p,i);p.sendMessage(msg("reward-vault").replace("%amount%",String.valueOf((int)a)));}}
        if("amethystcoin".equals(t)){if(playerPointsApi==null){p.sendMessage(msg("no-points"));return;}int a=ThreadLocalRandom.current().nextInt(getConfig().getInt("amethyst-coin-min",1),getConfig().getInt("amethyst-coin-max",3)+1);try{Method m=playerPointsApi.getClass().getMethod("give",UUID.class,int.class);Object r=m.invoke(playerPointsApi,p.getUniqueId(),a);if(!(r instanceof Boolean)||((Boolean)r)){consumeOne(p,i);p.sendMessage(msg("reward-points").replace("%amount%",String.valueOf(a)));}}catch(Exception x){p.sendMessage(msg("no-points"));}}
    }
    private void consumeOne(Player p,ItemStack i){if(i.getAmount()<=1)p.getInventory().setItemInMainHand(null);else i.setAmount(i.getAmount()-1);}
    private void registerRecipes() {
        ShapelessRecipe seedRecipe = new ShapelessRecipe(new NamespacedKey(this, "amethyst_seed"), seed());
        seedRecipe.addIngredient(new RecipeChoice.ExactChoice(special()));
        Bukkit.addRecipe(seedRecipe);

        ShapedRecipe c = new ShapedRecipe(new NamespacedKey(this, "crystal_coin"), crystal());
        c.shape("AAA", "AAA", "AAA");
        c.setIngredient('A', new RecipeChoice.ExactChoice(special()));
        Bukkit.addRecipe(c);

        ShapedRecipe a = new ShapedRecipe(new NamespacedKey(this, "amethyst_coin"), amCoin());
        a.shape("CCC", "CCC", "CCC");
        a.setIngredient('C', new RecipeChoice.ExactChoice(crystal()));
        Bukkit.addRecipe(a);
    }
    private boolean command(CommandSender s,Command c,String l,String[] a){if(a.length>=2&&a[0].equalsIgnoreCase("give")){Player p=Bukkit.getPlayerExact(a[1]);if(p==null){s.sendMessage(ChatColor.RED+"Игрок не найден.");return true;}String t=a.length>=3?a[2].toLowerCase():"seed";int n=a.length>=4?Math.max(1,Integer.parseInt(a[3])):1;ItemStack out=switch(t){case "seed"->seed();case "amethyst"->special();case "crystal"->crystal();case "amethystcoin"->amCoin();default->null;};if(out==null){s.sendMessage(msg("usage"));return true;}out.setAmount(n);p.getInventory().addItem(out);return true;}s.sendMessage(msg("usage"));return true;}
    private String msg(String k){return ChatColor.translateAlternateColorCodes('&',getConfig().getString("messages."+k,k));}
}
