package massim.simulation.game;

import massim.config.TeamConfig;
import massim.protocol.data.Position;
import massim.protocol.data.TaskInfo;
import massim.protocol.data.Thing;
import massim.protocol.messages.RequestActionMessage;
import massim.protocol.messages.SimEndMessage;
import massim.protocol.messages.SimStartMessage;
import massim.protocol.messages.scenario.InitialPercept;
import massim.protocol.messages.scenario.StepPercept;
import massim.simulation.game.environment.*;
import massim.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * State of the game.
 */
class GameState {

    private static Map<Integer, Terrain> terrainColors =
            Map.of(-16777216, Terrain.OBSTACLE, -1, Terrain.EMPTY, -65536, Terrain.GOAL);

    private int randomFail;
    private int attachLimit;
    private Map<String, Team> teams = new HashMap<>();
    private Map<String, Entity> agentToEntity = new HashMap<>();
    private Map<Entity, String> entityToAgent = new HashMap<>();
    private List<String> agentNames;

    private int step = -1;
    private Grid grid;
    private Map<String, GameObject> gameObjects = new HashMap<>();
    private Map<Position, Dispenser> dispensers = new HashMap<>();
    private Map<String, Task> tasks = new HashMap<>();
    private List<String> blockTypes = new ArrayList<>();

    GameState(JSONObject config, Set<TeamConfig> matchTeams) {
        // parse simulation config
        randomFail = config.getInt("randomFail");
        Log.log(Log.Level.NORMAL, "config.randomFail: " + randomFail);
        attachLimit = config.getInt("attachLimit");
        Log.log(Log.Level.NORMAL, "config.attachLimit: " + attachLimit);
        int numberOfBlockTypes = config.optInt("blockTypes");
        Log.log(Log.Level.NORMAL, "config.blockTypes: " + numberOfBlockTypes);
        for (int i = 0; i < numberOfBlockTypes; i++) {
            blockTypes.add("b" + i);
        }

        // create teams
        agentNames = new ArrayList<>();
        matchTeams.forEach(team -> {
            agentNames.addAll(team.getAgentNames());
            teams.put(team.getName(), new Team(team.getName()));
        });

        // create grid environment
        JSONObject gridConf = config.getJSONObject("grid");
        int gridX = gridConf.getInt("width");
        int gridY = gridConf.getInt("height");
        grid = new Grid(gridX, gridY, attachLimit);

        // read bitmap if available
        String mapFilePath = gridConf.optString("file");
        if (!mapFilePath.equals("")){
            File mapFile = new File(mapFilePath);
            if (mapFile.exists()) {
                try {
                    BufferedImage img = ImageIO.read(mapFile);
                    int width = Math.min(gridX, img.getWidth());
                    int height = Math.min(gridY, img.getHeight());
                    for (int x = 0; x < width; x++) { for (int y = 0; y < height; y++) {
                        grid.setTerrain(x, y, terrainColors.getOrDefault(img.getRGB(x, y), Terrain.EMPTY));
                    }}
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            else Log.log(Log.Level.ERROR, "File " + mapFile.getAbsolutePath() + " not found.");
        }

        // create entities
        JSONArray entities = config.getJSONArray("entities");
        for (var type = 0; type < entities.length(); type++) {
            JSONObject entityConf = entities.optJSONObject(type);
            if (entityConf != null){
                String roleName = entityConf.keys().next();
                int amount = entityConf.optInt(roleName, 0);
                for (var n = 0; n < amount; n++){
                    Position position = grid.findRandomFreePosition(); // entities from the same team start on the same spot
                    for (TeamConfig team: matchTeams) {
                        String agentName;
                        if(n < team.getAgentNames().size()) {
                            agentName = team.getAgentNames().get(n);
                        }
                        else {
                            agentName = team.getName() + "-unconfigured-" + n;
                            Log.log(Log.Level.ERROR, "Too few agents configured for team " + team.getName()
                                    + ", using agent name " + agentName + ".");
                        }
                        createEntity(position, agentName, team.getName());
                    }
                }
            }
        }
    }

    int getRandomFail() {
        return this.randomFail;
    }

    public Grid getGrid() {
        return grid;
    }

    /**
     * @return the agent entity of the given name or null if it does not exist
     */
    Entity getEntityByID(String goID) {
        GameObject entity = gameObjects.get(goID);
        if (!(entity instanceof Entity)) return null;
        return (Entity) entity;
    }

    Entity getEntityByName(String agentName) {
        return agentToEntity.get(agentName);
    }

    Map<String, SimStartMessage> getInitialPercepts(int steps) {
        Map<String, SimStartMessage> result = new HashMap<>();
        for (Entity e: entityToAgent.keySet()) {
            result.put(e.getAgentName(), new InitialPercept(e.getAgentName(), e.getTeamName(), steps));
        }
        return result;
    }

    Map<String, RequestActionMessage> prepareStep(int step) {
        this.step = step;
        return getStepPercepts();
    }

    private Map<String, RequestActionMessage> getStepPercepts(){
        Map<String, RequestActionMessage> result = new HashMap<>();
        Set<TaskInfo> allTasks = tasks.values().stream()
                .filter(t -> !t.isCompleted())
                .map(Task::toPercept)
                .collect(Collectors.toSet());
        for (String agent : agentNames) {
            Entity entity = getEntityByName(agent);
            int vision = entity.getVision();
            Position pos = entity.getPosition();
            Set<Thing> visibleThings = new HashSet<>();
            Map<String, Set<Position>> visibleTerrain = new HashMap<>();
            for (int dy = -vision; dy <= vision ; dy++) {
                int y = pos.y + dy;
                int visionLeft = vision - Math.abs(dy);
                for (int x = pos.x - visionLeft ; x <= pos.x + visionLeft; x++) {
                    Position currentPos = Position.of(x, y);
                    GameObject go = getGameObject(currentPos);
                    if (go != null) visibleThings.add(go.toPercept(entity.getPosition()));
                    Terrain terrain = grid.getTerrain(pos);
                    if (terrain != Terrain.EMPTY) {
                        visibleTerrain.computeIfAbsent(terrain.name,
                                t -> new HashSet<>()).add(currentPos.toLocal(entity.getPosition()));
                    }
                }
            }
            result.put(agent, new StepPercept(teams.get(entity.getTeamName()).getScore(), visibleThings, visibleTerrain,
                    allTasks, entity.getLastAction(), entity.getLastActionResult()));
        }
        return result;
    }

    Map<String, SimEndMessage> getFinalPercepts() {
        Map<String, SimEndMessage> result = new HashMap<>();
        List<Team> teamsSorted = new ArrayList<>(teams.values());
        teamsSorted.sort((t1, t2) -> (int) (t2.getScore() - t1.getScore()));
        Map<Team, Integer> rankings = new HashMap<>();
        for (int i = 0; i < teamsSorted.size(); i++) {
            rankings.put(teamsSorted.get(i), i + 1);
        }
        for (Entity e: entityToAgent.keySet()) {
            Team team = teams.get(e.getTeamName());
            result.put(e.getAgentName(), new SimEndMessage(team.getScore(), rankings.get(team)));
        }
        return result;
    }

    boolean move(Entity entity, String direction) {
        return grid.moveWithAttached(entity, direction, 1);
    }

    boolean rotate(Entity entity, boolean clockwise) {
        return grid.rotateWithAttached(entity, clockwise);
    }

    boolean attach(Entity entity, String direction) {
        Position target = entity.getPosition().moved(direction, 1);
        if (target == null) return false;
        String collidableID = grid.getCollidable(target);
        GameObject gameObject = gameObjects.get(collidableID);
        if (!(gameObject instanceof Attachable)) return false;
        Attachable a = (Attachable) gameObject;
        return !attachedToOpponent(a, entity) && grid.attach(entity, a);
    }

    boolean detach(Entity entity, String direction) {
        Position target = entity.getPosition().moved(direction, 1);
        Attachable a = getAttachable(target);
        if (a == null) return false;
        return grid.detach(entity, a);
    }

    boolean connectEntities(Entity entity, Position blockPos, Entity partnerEntity, Position partnerBlockPos) {
        Attachable block1 = getAttachable(blockPos.translate(entity.getPosition()));
        Attachable block2 = getAttachable(partnerBlockPos.translate(partnerEntity.getPosition()));

        if(!(block1 instanceof Block) || !(block2 instanceof Block)) return false;

        Set<Attachable> attachables = grid.getAllAttached(entity);
        if (attachables.contains(partnerEntity)) return false;
        if (!attachables.contains(block1)) return false;
        if (attachables.contains(block2)) return false;

        Set<Attachable> partnerAttachables = grid.getAllAttached(partnerEntity);
        if (!partnerAttachables.contains(block2)) return false;
        if (partnerAttachables.contains(block1)) return false;

        return grid.attach(block1, block2);
    }

    boolean requestBlock(Entity entity, String direction) {
        Position requestPosition = entity.getPosition().moved(direction, 1);
        Dispenser dispenser = dispensers.get(requestPosition);
        if (dispenser != null && grid.isFree(requestPosition)){
            createBlock(requestPosition, dispenser.getBlockType());
            return true;
        }
        return false;
    }

    boolean submitTask(Entity e, String taskName) {
        Task task = tasks.get(taskName);
        if (task == null || task.isCompleted()) return false;
        Position ePos = e.getPosition();
        if (grid.getTerrain(ePos) != Terrain.GOAL) return false;
        Set<Attachable> attachedBlocks = grid.getAllAttached(e);
        for (Map.Entry<Position, String> entry : task.getRequirements().entrySet()) {
            Position pos = entry.getKey();
            String reqType = entry.getValue();
            Position checkPos = Position.of(pos.x + ePos.x, pos.y + ePos.y);
            Attachable actualBlock = getAttachable(checkPos);
            if (actualBlock instanceof Block
                && ((Block) actualBlock).getBlockType().equals(reqType)
                && attachedBlocks.contains(actualBlock)) {
                continue;
            }
            return false;
        }
        task.getRequirements().keySet().forEach(pos -> {
            Attachable a = getAttachable(pos.translate(e.getPosition()));
            if (a != null) {
                grid.removeAttachable(a);
                gameObjects.remove(a.getID());
            }
        });
        teams.get(e.getTeamName()).addScore(task.getReward());
        return true;
    }

    Task createTask() {
        // TODO use more config parameters
        Task t = Task.generate("task" + tasks.values().size(), step + 200, 5, blockTypes);
        tasks.put(t.getName(), t);
        return t;
    }

    Entity createEntity(Position xy, String name, String teamName) {
        Entity e = grid.createEntity(xy, name, teamName);
        registerGameObject(e);
        agentToEntity.put(name, e);
        entityToAgent.put(e, name);
        return e;
    }

    Block createBlock(Position xy, String blockType) {
        Block b = grid.createBlock(xy, blockType);
        registerGameObject(b);
        return b;
    }

    void createDispenser(Position xy, String blockType) {
        Dispenser d = new Dispenser(xy, blockType);
        registerGameObject(d);
        dispensers.put(xy, d);
    }

    private void registerGameObject(GameObject o) {
        this.gameObjects.put(o.getID(), o);
    }

    private Attachable getAttachable(Position position) {
        GameObject go = getGameObject(position);
        return (go instanceof Attachable)? (Attachable) go : null;
    }

    private GameObject getGameObject(Position pos) {
        return gameObjects.get(grid.getCollidable(pos));
    }

    private boolean attachedToOpponent(Attachable a, Entity entity) {
        return grid.getAllAttached(a).stream().anyMatch(other -> other instanceof Entity && ofDifferentTeams((Entity) other, entity));
    }

    private boolean ofDifferentTeams(Entity e1, Entity e2) {
        return !e1.getTeamName().equals(e2.getTeamName());
    }

    public JSONObject takeSnapshot() {
        JSONObject snapshot = new JSONObject();
        JSONArray entities = new JSONArray();
        snapshot.put("entities", entities);
        JSONArray blocks = new JSONArray();
        snapshot.put("blocks", blocks);
        JSONArray dispensers = new JSONArray();
        snapshot.put("dispensers", dispensers);
        JSONArray taskArr = new JSONArray();
        snapshot.put("tasks", taskArr);
        for (GameObject o : gameObjects.values()) {
            if (o instanceof Entity) {
                JSONObject entity = new JSONObject();
                entity.put("id", o.getID());
                entity.put("x", ((Entity) o).getPosition().x);
                entity.put("y", ((Entity) o).getPosition().y);
                entity.put("name", ((Entity) o).getAgentName());
                entity.put("team", ((Entity) o).getTeamName());
                entities.put(entity);
            }
            else if (o instanceof Block) {
                JSONObject block = new JSONObject();
                block.put("x", ((Block) o).getPosition().x);
                block.put("y", ((Block) o).getPosition().y);
                block.put("type", ((Block) o).getBlockType());
                blocks.put(block);
            }
            else if (o instanceof Dispenser) {
                JSONObject dispenser = new JSONObject();
                dispenser.put("id", o.getID());
                dispenser.put("x", ((Dispenser) o).getPosition().x);
                dispenser.put("y", ((Dispenser) o).getPosition().y);
                dispenser.put("type", ((Dispenser) o).getBlockType());
                dispensers.put(dispenser);
            }
        }
        tasks.values().stream().filter(t -> !t.isCompleted()).forEach(t -> {
            JSONObject task  = new JSONObject();
            task.put("name", t.getName());
            task.put("deadline", t.getDeadline());
            task.put("reward", t.getReward());
            JSONArray requirementsArr = new JSONArray();
            task.put("requirements", requirementsArr);
            t.getRequirements().forEach((pos, type) -> {
                JSONObject requirement = new JSONObject();
                requirement.put("x", pos.x);
                requirement.put("y", pos.y);
                requirement.put("type", type);
                requirementsArr.put(requirement);
            });
            taskArr.put(task);
        });
        return snapshot;
    }

    public JSONObject getResult() {
        JSONObject result =  new JSONObject();
        teams.values().forEach(t -> {
            JSONObject teamResult = new JSONObject();
            teamResult.put("score", t.getScore());
            result.put(t.getName(), teamResult);
        });
        return result;
    }
}