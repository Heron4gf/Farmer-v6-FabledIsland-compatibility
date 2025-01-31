package xyz.geik.farmer.model;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.Main;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.database.DBConnection;
import xyz.geik.farmer.database.DBQueries;
import xyz.geik.farmer.model.inventory.FarmerInv;
import xyz.geik.farmer.model.inventory.FarmerItem;
import xyz.geik.farmer.model.user.FarmerPerm;
import xyz.geik.farmer.model.user.User;
import xyz.geik.farmer.modules.autoharvest.AutoHarvest;
import xyz.geik.farmer.modules.autoseller.AutoSeller;
import xyz.geik.farmer.modules.spawnerkiller.SpawnerKiller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Main farmer object
 */
@Setter
@Getter
public class Farmer {

    // Region id of farmer
    private String regionID;

    // User list of farmer
    private Set<User> users;

    // Inventory of farmer
    private FarmerInv inv;

    // Level of farmer
    private FarmerLevel level;

    // State shows farmer collect state
    // id is farmer id generated by database
    private int state, id;

    private HashMap<String, Boolean> moduleAttributes = new HashMap<>();

    /**
     * Gets attribute from Farmer
     *
     * @param attribute
     * @return
     */
    public boolean getAttributeStatus(String attribute) {
        if (getModuleAttributes().containsKey(attribute))
            return getModuleAttributes().get(attribute);
        else return getDefaultStatus(attribute);
    }

    /**
     * Change attribute
     *
     * @param attribute
     * @return
     */
    public boolean changeAttribute(String attribute) {
        if (getModuleAttributes().containsKey(attribute)) {
            getModuleAttributes().remove(attribute);
            return getDefaultStatus(attribute);
        }
        else {
            boolean status = !getDefaultStatus(attribute);
            getModuleAttributes().put(attribute, status);
            return status;
        }
    }

    /**
     * Get default status of attribute
     *
     * @param attribute
     * @return
     */
    private boolean getDefaultStatus(@NotNull String attribute) {
        switch (attribute) {
            case "spawnerkiller":
                return SpawnerKiller.getInstance().isDefaultStatus();
            case "autoharvest":
                return AutoHarvest.getInstance().isDefaultStatus();
            case "autoseller":
                return AutoSeller.getInstance().isDefaultStatus();
            default:
                return false;
        }
    }

    /**
     * First constructor of farmer which already created before
     * and loads it again.
     *
     * @param id
     * @param regionID
     * @param users
     * @param inv
     * @param level
     * @param state
     */
    public Farmer(int id, String regionID, Set<User> users,
                  FarmerInv inv, FarmerLevel level, int state) {
        this.id = id;
        this.regionID = regionID;
        this.users = users;
        this.inv = inv;
        this.level = level;
        this.state = state;
    }

    /**
     * Second constructor of farmer which creates fresh farmer.
     *
     * @param regionID
     * @param ownerUUID
     */
    public Farmer(String regionID, UUID ownerUUID, int level) {
        this.regionID = regionID;
        Set<User> users = new LinkedHashSet<>();
        this.users = users;
        this.inv = new FarmerInv();
        this.level = FarmerLevel.getAllLevels().get(level);
        this.state = 1;
        FarmerManager.getFarmers().put(regionID, this);
        DBQueries.createFarmer(this, ownerUUID);
    }

    /**
     * Gets owner uuid of farmer
     *
     * @return
     */
    public UUID getOwnerUUID() {
        return users.stream().filter(this::isUserOwner).findFirst().get().getUuid();
    }

    /**
     * Is the user owner returns true or false
     *
     * @param user
     * @return
     */
    private boolean isUserOwner(@NotNull User user) {
        return user.getPerm().equals(FarmerPerm.OWNER);
    }

    /**
     * Gets users without owner
     *
     * @return
     */
    public Set<User> getUsersWithoutOwner() {
        return users.stream().filter(this::isUserNotOwner).collect(Collectors.toSet());
    }

    /**
     * Is user not owner
     *
     * @param user
     * @return
     */
    private boolean isUserNotOwner(@NotNull User user) {
        return user.getPerm().equals(FarmerPerm.OWNER);
    }

    /**
     * Saves farmer to the database
     */
    public void saveFarmerAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            // Save farmer to db
            try (Connection con = DBConnection.connect()) {
                // It requires sync on another methods splitting it
                // to another method because of that
                saveFarmer(con);
            }
            catch (Exception e) { e.printStackTrace(); }
        });
    }

    /**
     * Saves farmer to the database requires connection
     * Because of multiple requirements can use one connection
     *
     * @param con
     * @throws SQLException
     */
    public void saveFarmer(@NotNull Connection con) throws SQLException {
        final String query = "UPDATE Farmers SET regionID = ?, state = ?, items = ?, level = ? WHERE id = ?";
        PreparedStatement statement = con.prepareStatement(query);
        statement.setString(1, getRegionID());
        statement.setInt(2, getState());
        String serializedItems = FarmerItem.serializeItems(getInv().getItems());
        statement.setString(3, (serializedItems == "") ? null : serializedItems);
        statement.setInt(4, FarmerLevel.getAllLevels().indexOf(getLevel()));
        statement.setInt(5, getId());
        statement.executeUpdate();
        // closing statement
        statement.close();
    }

    /**
     * Adds user to farmer with COOP role
     *
     * @param uuid
     * @param name
     */
    public void addUser(UUID uuid, String name) {
        addUser(uuid, name, FarmerPerm.COOP);
    }

    /**
     * Adds user to farmer with desired role
     *
     * @param uuid
     * @param name
     * @param perm
     */
    public void addUser(UUID uuid, String name, FarmerPerm perm) {
        this.getUsers().add(new User(this.getId(), name, uuid, perm));
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            final String QUERY = "INSERT INTO FarmerUsers (farmerId, name, uuid, role) VALUES (?, ?, ?, ?)";
            try (Connection con = DBConnection.connect()) {
                PreparedStatement statement = con.prepareStatement(QUERY);
                statement.setInt(1, this.getId());
                statement.setString(2, name);
                statement.setString(3, uuid.toString());
                statement.setInt(4, FarmerPerm.getRoleId(perm));
                statement.executeUpdate();
                statement.close();
            }
            catch (Exception e) { e.printStackTrace(); }
        });
    }

    /**
     * Delete user from farmer
     *
     * @param user
     * @return
     */
    public boolean removeUser(@NotNull User user) {
        if (user.getPerm().equals(FarmerPerm.OWNER))
            return false;
        this.getUsers().remove(user);
        Bukkit.getScheduler().runTaskAsynchronously(Main.getInstance(), () -> {
            final String QUERY = "DELETE FROM FarmerUsers WHERE uuid = ? AND farmerId = ?";
            try (Connection con = DBConnection.connect()) {
                PreparedStatement statement = con.prepareStatement(QUERY);
                statement.setString(1, user.getUuid().toString());
                statement.setInt(2, this.getId());
                statement.executeUpdate();
                statement.close();
            }
            catch (Exception e) { e.printStackTrace(); }
        });
        return true;
    }
}
