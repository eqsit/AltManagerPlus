package com.dummymod.ai;

import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.*;
import com.dummymod.dummy.baritone.BaritoneBridge;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import baritone.api.pathing.goals.GoalBlock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Future;

public final class AiController {
    private static final java.util.concurrent.ExecutorService WORKERS=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final PlayerSession session;
    private Conversation history;
    private String historyKey,owner="";
    private Building building;
    private final Deque<String> pending=new ArrayDeque<>(),outgoing=new ArrayDeque<>();
    private boolean busy;
    private long generation,lastSend;
    private Future<?> request;
    private int jumpTicks;
    private String dimension;
    public AiController(PlayerSession session){this.session=session;}
    public String status(){return busy?"ИИ думает":building==null?"ИИ: чат":building.status();}
    public int historySize(){return history==null?0:history.messages().size();}
    public boolean controlsInput(){return building!=null && building.controlsInput();}
    public void receive(String sender,String message) {
        DummyConfig c=DummyConfig.getInstance();
        if(session.main || !session.isValid() || !c.aiEnabled || !ChatAccess.allowed(c.aiChatWhitelist,sender))return;
        if(message==null || message.isBlank())return;
        loadHistory(sender);
        String clean=message.strip();
        if(clean.length()>4000)clean=clean.substring(0,4000);
        // Stop and pause are immediate, even while a model request is in flight.
        String command=clean.toLowerCase(Locale.ROOT).replaceAll("[.!]+$","").trim();
        FlightSpeed speed=FlightSpeed.command(command);
        if(speed!=null){history.flightSpeed=speed;history.add("user",clean);if(building!=null)building.flightSpeed(speed);say("Скорость полёта: "+speed.label+". Возле блока замедляюсь для точной установки.");history.save();return;}
        if(Set.of("сохрани проект","сохрани схему","сохрани постройку").contains(command)){history.add("user",clean);history.archiveCurrent();history.save();say(history.plan==null?"Пока нет проекта для сохранения.":"Схема сохранена. Незаконченные проекты доступны в меню ИИ → «Проекты / память».");return;}
        boolean creativeMention=command.matches(".*(?:креатив\\S*|creative).*"),giveMention=command.matches(".*(?:\\bgive\\b|выдавай|выдай|выдавать|ресы сам|ресурсы сам).*" );
        if(creativeMention)history.creative=!command.matches(".*(?:без креатив|не креатив|выключи креатив|отключи креатив).*" );
        if(command.contains("выживани") || command.contains("survival"))history.creative=false;
        if(giveMention)history.autoGive=!command.matches(".*(?:не выда|без give|выключи give|отключи give).*" );
        if(building!=null)building.mode(history.creative,history.autoGive);
        if(creativeMention && history.creative && !CreativeBuilder.available(session))say("Для полёта сервер должен дать настоящий creative именно этой дамми. Сейчас у меня режим выживания.");
        if(Set.of("стоп","stop","отмена","cancel","пауза","pause").contains(command)) {
            cancelRequest();pending.clear();outgoing.clear();BaritoneBridge.execute(session,"stop");session.botController.stop();session.autoclicker.enabled=false;
            if(building!=null){if(command.equals("пауза") || command.equals("pause"))building.pause();else building.stop();}
            history.add("user",clean);say("Остановилась. Проект сохранён; могу продолжить по твоей команде.");history.save();return;
        }
        if((Set.of("строй","строить","начинай","build","продолжай","продолжить","resume").contains(command) || command.matches("(?:строй|начинай|продолжай) (?:в )?(?:креативе|creative|выживании|survival)") || command.matches("(?:продолжай|продолжи|продолжить) (?:с?строить|стройку|постройку).*")) && building!=null){
            cancelRequest();pending.clear();history.add("user",clean);startBuilding();history.save();return;
        }
        if(!AiConnection.get().ready()){say("Укажи ключ, URL и модель OmniRoute в меню ИИ.");return;}
        if(pending.size()>=24){say("У меня уже много сообщений в очереди. Дай немного времени ответить.");return;}
        pending.addLast(clean);pump();
    }
    private void loadHistory(String sender) {
        MinecraftClient client=MinecraftClient.getInstance();
        String server=client.getCurrentServerEntry()==null?"unknown":client.getCurrentServerEntry().address;
        String key=server+"|"+session.displayName().toLowerCase(Locale.ROOT)+"|"+sender.toLowerCase(Locale.ROOT);
        if(key.equals(historyKey))return;
        cancelRequest();pending.clear();outgoing.clear();if(building!=null)building.stop();building=null;
        historyKey=key;owner=sender;dimension=session.world.getRegistryKey().getValue().toString();
        String file=UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString()+".json";
        history=new Conversation(FabricLoader.getInstance().getConfigDir().resolve("dummymod-ai-history").resolve(file));
        if(history.plan!=null && dimension.equals(history.dimension))try{building=new Building(session,BuildPlan.parse(history.plan),this::say);building.mode(history.creative,history.autoGive);}catch(Exception ignored){}
        else {history.archiveCurrent();history.plan=null;history.planComplete=false;if(!dimension.equals(history.dimension))history.clearRejected();}
        history.dimension=dimension;
    }
    private void pump() {
        if(busy || pending.isEmpty() || !session.isValid())return;
        String message=pending.removeFirst();history.add("user",message);history.save();
        String prompt=prompt();List<Conversation.Message> messages=history.requestMessages();long token=++generation;busy=true;
        org.slf4j.LoggerFactory.getLogger("DummyMod-AI").info("AI request started for {}, generation={}, history_messages={}",session.displayName(),token,messages.size());
        request=WORKERS.submit(()-> {
            PlanRepair.Result response=null;Exception error=null;
            try{response=PlanRepair.ask(prompt,messages,OmniClient::ask,(attempt,draft,reason)->MinecraftClient.getInstance().execute(()->{
                if(token!=generation || !session.isValid() || !DummyConfig.getInstance().aiEnabled || !ChatAccess.allowed(DummyConfig.getInstance().aiChatWhitelist,owner))return;
                history.reject(draft,reason);history.save();
                org.slf4j.LoggerFactory.getLogger("DummyMod-AI").warn("AI blueprint repair {}, generation={}: {}",attempt,token,reason);
                if(attempt==1)say("В чертеже есть ошибка. Проверяю и исправляю схему перед строительством.");
            }));}catch(InterruptedException cancelled){Thread.currentThread().interrupt();return;}catch(Exception e){error=e;}
            PlanRepair.Result result=response;Exception failure=error;
            MinecraftClient.getInstance().execute(()->complete(token,result,failure));
        });
    }
    private void complete(long token,PlanRepair.Result response,Exception error) {
            if(token!=generation)return;
            busy=false;
            DummyConfig c=DummyConfig.getInstance();
            if(!session.isValid() || !c.aiEnabled || !ChatAccess.allowed(c.aiChatWhitelist,owner))return;
            if(error!=null){Throwable cause=error.getCause()==null?error:error.getCause();
                org.slf4j.LoggerFactory.getLogger("DummyMod-AI").warn("AI request failed for {} ({})",session.displayName(),cause instanceof ApiFailure f?f.getMessage():cause.getClass().getSimpleName());
                say(cause instanceof ApiFailure f?f.userMessage():cause instanceof PlanRepair.InvalidPlan e?"Не получилось исправить схему: "+safeError(e)+". Черновик и прежний проект сохранены; напиши «исправь схему».":"Не удалось получить ответ OmniRoute после повторных попыток. Проект и переписка сохранены, текущая стройка продолжается.");}
            else {
                org.slf4j.LoggerFactory.getLogger("DummyMod-AI").info("AI response received for {}, generation={}",session.displayName(),token);
                try{apply(response.response(),response.plan());}catch(Exception e){
                    if(response.response().has("plan"))history.reject(response.response(),safeError(e));
                    org.slf4j.LoggerFactory.getLogger("DummyMod-AI").warn("AI project rejected for {}: {}",session.displayName(),safeError(e));
                    say("Не смогла выполнить проект: "+safeError(e)+". Можем изменить план.");
                }
            }
            history.save();pump();
    }
    private static String safeError(Exception e){String s=e instanceof IllegalArgumentException?e.getMessage():"некорректный ответ модели";return s==null?"неверный проект":s.substring(0,Math.min(s.length(),180));}
    private String prompt() {
        Map<String,Integer> inventory=new TreeMap<>();
        for(int i=0;i<36;i++){ItemStack s=session.player.getInventory().getStack(i);if(!s.isEmpty())inventory.merge(Registries.ITEM.getId(s.getItem()).toString(),s.getCount(),Integer::sum);}
        BlockPos p=session.player.getBlockPos();
        return """
            Ты дружелюбная Minecraft-дамми и архитектор. Общайся по-русски естественно, кратко, без наглости. Твой хозяин в белом списке.
            Ты сама проектируешь ЛЮБУЮ запрошенную постройку. В survival Baritone строит из выданных материалов; в настоящем creative отдельный исполнитель летает и ставит блоки без служебных опор.
            Обсуждай размер, место и материалы, предлагай дешёвые замены, уважай бюджет, пересчитывай проект при изменении ресурсов.
            Не требуй редкие блоки без необходимости. Не утверждай, что строительство началось или закончено без состояния исполнения.
            Сначала выдай plan и обсуждай ресурсы. start разрешён только когда хозяин явно согласился строить существующий проект.
            Если хозяин просит продолжить незаконченный проект, меняет режим движения или выживание/креатив, сохраняй ту же схему и координаты. Не создавай новый plan без просьбы перепроектировать или изменить материалы/постройку.
            Ресурсы считаются кодом, передаются бросанием рядом с дамми, строительство идёт частями по 128 блоков, можно приносить партиями.
            Если системное состояние creative_active=true, не проси физические ресурсы: ты берёшь блоки из творческого инвентаря. Нельзя объявлять креатив включённым, если сервер дал survival. Упоминание креатива хозяином разрешает полёт, но не меняет серверный gamemode.
            После просьбы выдавать себе материалы auto_give=true разрешает /give только самой себе, по одному стеку и только если сервер дал эту команду. Не обещай выдачу без give_available=true.
            Если ресурсы закончились, можно обсудить замену: action=plan с новым полным проектом. Изменение проекта требует нового согласия.
            Ответ СТРОГО JSON без markdown: {"reply":"текст","action":"chat|plan|start|pause|resume|stop|goto|follow|jump|mine|break|give",...}.
            В креативе предпочитай ходить по доступной поверхности, летать только для подъёма и недоступных мест; после посадки ходить. Для полёта хозяин может написать «летай быстро», «летай медленно», «летай авто».
            Если обычный вход недоступен и ломание разрешено, исполнитель может открыть небольшой временный проход в обычной стене и восстановить её после прохода. Контейнеры и опасные опоры для прохода не разбираются. Проход и временные опоры для установки отдельных декоративных блоков сохраняются на диск и убираются до завершения проекта.
            Для action=plan: "plan":{"name":"...","origin":[ABSOLUTE_X,ABSOLUTE_Y,ABSOLUTE_Z],"operations":[{"shape":"box|hollow_box|sphere|cylinder","from":[x,y,z],"to":[x,y,z],"block":"minecraft:oak_planks"}]}.
            Координаты from/to относительные неотрицательные, включительные, from<=to, <=95. До 50000 ячеек и 1024 операций, большие проекты дели на отдельные части.
            ВАЖНО: абсолютные мировые координаты используются ТОЛЬКО в origin. Например origin=[1200,70,-900], from=[0,0,0], to=[19,12,15] задают постройку 20×13×16; никогда не пиши [1200,70,-900] в from/to. Проверяй каждую ось каждой операции: 0<=from[i]<=to[i]<=95. Для подвала снизь origin.y и сдвинь ВСЕ относительные Y, сохранив мировое положение постройки.
            Если в контексте есть отклонённый черновик, он не является согласованным проектом. Используй его для исправления по просьбе хозяина; не запускай строительство отклонённой схемы.
            Операции идут по порядку; более поздние заменяют блоки прежних. Точечный блок = box с одинаковыми from/to.
            Полости hollow_box игнорируют внутренние блоки: для расчистки комнаты/двери добавь явный box с minecraft:air.
            sphere = заполненный эллипсоид в указанном ящике; cylinder = эллиптический цилиндр по Y. Комбинируй любые формы и блоки, не ограничивайся домом.
            Доступны vanilla и установленные блоки. Используй размещаемые блоки; учитывай поддержку, доступ внутрь, лестницы, проёмы, кровлю. Не создавай висящие песок/гравий.
            Версия Minecraft 1.21.11: железная цепь называется minecraft:iron_chain, старое имя minecraft:chain не используй.
            Блоки могут иметь свойства minecraft:oak_stairs[facing=north,half=bottom], oak_slab[type=top]. Дверям нужны lower/upper, кроватям foot/head. Избегай непонятных свойств и неподдерживаемых технических блоков/жидкостей.
            Координаты: +X=east, -X=west, +Z=south, -Z=north. У ступеней facing указывает к высокой половине: при подъёме на север facing=north. У крыши высокая половина смотрит к коньку, у ступеньки перед южным входом — на север, внутрь дома. half=top — перевёрнутая ступень, half=bottom — обычная. Для ladder facing смотрит от опорной стены; стену ставь позади лестницы, в направлении opposite(facing). У кровати head находится на соседней клетке в направлении facing от foot. Не задавай случайные shape, powered, connection или waterlogged: они зависят от соседей и окружения, а не только от поворота при установке.
            Строй над землёй, выбирай безопасное место рядом (по умолчанию +3 X от дамми), учитывай ближайшие блоки. Связывай конструкцию с землёй или существующими опорами: креативный полёт не позволяет ставить отдельные блоки посреди воздуха. Сначала фундамент, затем стены и крыша. Оставляй проходы и подходы. Без явной команды не сноси чужие здания.
            goto: "target":[absolute x,y,z]. follow: "player":"ник". jump: разовый прыжок. mine: "blocks":["minecraft:stone"], "count":1..4096.
            give: только по явной просьбе хозяина и auto_give=true, "item":"minecraft:stone", "count":1..64, только размещаемые блоки для постройки, только самой себе. Если есть проект, автоматическая выдача уже предусмотрена исполнителем.
            break: только по ЯВНОЙ просьбе хозяина, "from":[absolute x,y,z],"to":[absolute x,y,z], небольшой регион. Нельзя вызывать произвольные команды, файлы или shell. Единственная доступная серверная команда — ограниченная give.
            Системное состояние и расчёты достовернее истории. Ты можешь ходить, прыгать, добывать, ломать и строить через перечисленные действия.
            """+"\nТвоё имя: "+session.displayName()+"; хозяин: "+owner+"; позиция: "+p.toShortString()+"; мир: "+dimension+"; game_mode="+session.interactionManager.getCurrentGameMode()+"; creative_requested="+history.creative+"; creative_active="+(history.creative && CreativeBuilder.available(session))+"; auto_give="+history.autoGive+"; give_available="+Supply.canGive(session)+"; инвентарь: "+inventory+"; ломание разрешено="+DummyConfig.getInstance().aiAllowBreaking+"; ближайшие блоки="+nearby()+"; текущая постройка="+(building==null?"нет":building.snapshot());
    }
    private String nearby(){List<String> blocks=new ArrayList<>();BlockPos p=session.player.getBlockPos();for(int x=-3;x<=5;x++)for(int z=-3;z<=5;z++)for(int y=-1;y<=2;y++){BlockPos q=p.add(x,y,z);var s=session.world.getBlockState(q);if(!s.isAir())blocks.add(q.toShortString()+":"+Registries.BLOCK.getId(s.getBlock()));}return blocks.toString();}
    private void apply(JsonObject r,BuildPlan validatedPlan) {
        String action=r.has("action")?r.get("action").getAsString():"chat";
        switch(action) {
            case "chat" -> {}
            case "plan" -> {
                BuildPlan plan=validatedPlan==null?BuildPlan.parse(r.getAsJsonObject("plan")):validatedPlan;Building replacement=new Building(session,plan,this::say);
                if(building!=null)building.stop();BaritoneBridge.execute(session,"stop");session.botController.stop();
                history.archiveCurrent();building=replacement;building.mode(history.creative,history.autoGive);history.plan=plan.source;history.planComplete=false;history.clearRejected();say(building.description());
            }
            case "start","resume" -> startBuilding();
            case "give" -> {
                if(!history.autoGive)throw new IllegalArgumentException("Выдача себе материалов пока не разрешена хозяином");
                var id=blockIdentifier(r.get("item").getAsString());var block=Registries.BLOCK.get(id);
                if(block.asItem()==net.minecraft.item.Items.AIR)throw new IllegalArgumentException("Нужен размещаемый блок");
                int count=r.has("count")?r.get("count").getAsInt():64;Supply.give(session,Registries.ITEM.getId(block.asItem()).toString(),Math.min(count,block.asItem().getMaxCount()));
            }
            case "pause" -> {if(building!=null)building.pause();BaritoneBridge.execute(session,"pause");}
            case "stop" -> {if(building!=null)building.stop();BaritoneBridge.execute(session,"stop");session.botController.stop();session.autoclicker.enabled=false;}
            case "goto" -> {suspendBuilding();var b=requiredBaritone();b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(coords(r,"target")));}
            case "follow" -> {suspendBuilding();String name=r.has("player")?r.get("player").getAsString():owner;if(!name.matches("[A-Za-z0-9_]{3,16}"))throw new IllegalArgumentException("Неверный ник");BaritoneBridge.execute(session,"follow player "+name);}
            case "jump" -> {suspendBuilding();jumpTicks=3;requiredBaritone().getInputOverrideHandler().setInputForceState(baritone.api.utils.input.Input.JUMP,true);}
            case "mine" -> {
                if(!DummyConfig.getInstance().aiAllowBreaking)throw new IllegalArgumentException("Ломание выключено в меню ИИ");
                List<String> names=new ArrayList<>();JsonArray blocks=r.getAsJsonArray("blocks");if(blocks==null || blocks.isEmpty() || blocks.size()>12)throw new IllegalArgumentException("Нужно 1–12 блоков для добычи");
                for(JsonElement el:blocks)names.add(blockIdentifier(el.getAsString()).toString());
                int count=r.has("count")?r.get("count").getAsInt():64;if(count<1 || count>4096)throw new IllegalArgumentException("Добыча: 1–4096 предметов");
                suspendBuilding();if(!BaritoneBridge.execute(session,"mine "+count+" "+String.join(" ",names)))throw new IllegalArgumentException("Baritone не принял команду добычи");
            }
            case "break" -> {
                if(!DummyConfig.getInstance().aiAllowBreaking)throw new IllegalArgumentException("Ломание выключено в меню ИИ");
                BlockPos a=coords(r,"from"),b=coords(r,"to");long volume=((long)Math.abs(a.getX()-b.getX())+1)*(Math.abs(a.getY()-b.getY())+1)*(Math.abs(a.getZ()-b.getZ())+1);
                if(volume>4096 || a.getSquaredDistance(session.player.getBlockPos())>9216 || b.getSquaredDistance(session.player.getBlockPos())>9216)throw new IllegalArgumentException("Для сноса выбери область до 4096 блоков рядом со мной");
                suspendBuilding();requiredBaritone().getBuilderProcess().clearArea(a,b);
            }
            default -> throw new IllegalArgumentException("Неизвестное действие модели");
        }
        if(r.has("reply") && !r.get("reply").isJsonNull() && !action.equals("plan")){String reply=r.get("reply").getAsString();if(!reply.isBlank())say(reply);}
    }
    private net.minecraft.util.Identifier blockIdentifier(String n){var id=BlockNames.resolve(n);if(id==null || !n.matches("[a-z0-9_:]+") || !Registries.BLOCK.containsId(id))throw new IllegalArgumentException("Неизвестный блок: "+n);return id;}
    private BlockPos coords(JsonObject r,String field) {JsonArray a=r.getAsJsonArray(field);if(a==null || a.size()!=3)throw new IllegalArgumentException("Нужны 3 координаты");int[] c=new int[3];for(int i=0;i<3;i++){double n=a.get(i).getAsDouble();if(!Double.isFinite(n) || n!=Math.rint(n) || Math.abs(n)>29999000)throw new IllegalArgumentException("Неверные координаты");c[i]=(int)n;}if(c[1]<session.world.getBottomY() || c[1]>session.world.getTopYInclusive())throw new IllegalArgumentException("Координата Y вне мира");return new BlockPos(c[0],c[1],c[2]);}
    private baritone.api.IBaritone requiredBaritone(){var b=BaritoneBridge.resolve(session);if(b==null)throw new IllegalArgumentException("Baritone недоступен");return b;}
    private void suspendBuilding(){if(building!=null)building.pause();session.botController.stop();BaritoneBridge.execute(session,"stop");}
    private void startBuilding(){if(history.rejectedResponse!=null){say("Последняя новая схема ещё не исправлена. Напиши «исправь схему» либо выбери прежний проект в меню «Проекты / память».");return;}if(building==null){say("Сначала давай спроектируем постройку.");return;}session.botController.stop();session.autoclicker.enabled=false;BaritoneBridge.execute(session,"stop");building.mode(history.creative,history.autoGive);building.start();say(building.creative()?(DummyConfig.getInstance().aiPreferWalking?"Начинаю в креативе: хожу по доступной поверхности, для подъёма летаю без служебных опор. Блоки беру сама.":"Начинаю в креативе: летаю без служебных опор, блоки беру сама."):history.autoGive && Supply.canGive(session)?"Проект согласован. Запрашиваю материалы командой /give себе и строю по частям.":"Проект согласован. Проверяю ресурсы и начинаю по частям, как только они есть.");if(history.autoGive && !Supply.canGive(session))say("У этой дамми нет доступной команды /give. Понадобятся материалы или права на сервере.");}
    private void say(String text) {
        String clean=text.replaceAll("[\\p{Cntrl}§]"," ").trim();if(clean.isEmpty())return;
        if(clean.length()>1800)clean=clean.substring(0,1800);
        if(history!=null)history.add("assistant",clean);
        for(int start=0;start<clean.length() && outgoing.size()<32;) {
            int end=Math.min(start+220,clean.length());if(end<clean.length()){int space=clean.lastIndexOf(' ',end);if(space>start+100)end=space;}
            outgoing.addLast("[ИИ] "+clean.substring(start,end).strip());start=end;
        }
    }
    public void tick() {
        DummyConfig c=DummyConfig.getInstance();
        if(!c.aiEnabled || !ChatAccess.allowed(c.aiChatWhitelist,owner)) {if(building!=null && building.approved())building.pause();if(busy)cancelRequest();outgoing.clear();pending.clear();return;}
        if(dimension!=null && !dimension.equals(session.world.getRegistryKey().getValue().toString())){if(building!=null)building.stop();building=null;dimension=session.world.getRegistryKey().getValue().toString();if(history!=null){history.archiveCurrent();history.plan=null;history.planComplete=false;history.clearRejected();history.dimension=dimension;history.save();}say("Мы перешли в другой мир. Старый проект сохранён; выберем новое место.");}
        if(jumpTicks>0 && --jumpTicks==0 && session.baritone!=null)session.baritone.getInputOverrideHandler().setInputForceState(baritone.api.utils.input.Input.JUMP,false);
        if(building!=null){building.flightSpeed(history!=null && history.flightSpeed!=null?history.flightSpeed:c.aiCreativeFlightSpeed);building.preferWalking(c.aiPreferWalking);building.tick(c.aiAllowBreaking);if(building.finished() && history!=null && !history.planComplete){history.planComplete=true;history.save();}}
        if(!outgoing.isEmpty() && System.currentTimeMillis()-lastSend>1800){String msg=outgoing.removeFirst();session.networkHandler.sendChatMessage(msg);lastSend=System.currentTimeMillis();if(history!=null)history.save();}
        pump();
    }
    private void cancelRequest(){generation++;busy=false;if(request!=null)request.cancel(true);request=null;}
    public List<Conversation.SavedPlan> savedProjects() {
        var client=MinecraftClient.getInstance();
        if(!session.isValid() || client.player==null)return List.of();
        String sender=DummyManager.mainSession!=null && DummyManager.mainSession.player!=null?DummyManager.mainSession.player.getGameProfile().name():client.player.getGameProfile().name();
        if(!ChatAccess.allowed(DummyConfig.getInstance().aiChatWhitelist,sender))return List.of();
        loadHistory(sender);List<Conversation.SavedPlan> result=new ArrayList<>();
        if(history.plan!=null && !history.planComplete)result.add(new Conversation.SavedPlan("current",history.plan.has("name")?history.plan.get("name").getAsString():"Постройка",history.dimension,history.plan.deepCopy(),0));
        for(var saved:history.projects())if(result.stream().noneMatch(p->p.source().equals(saved.source())))result.add(saved);
        return result;
    }
    public void resumeSaved(String id) {
        var saved=savedProjects().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("Проект не найден для этой дамми"));
        if(!saved.dimension().equals(session.world.getRegistryKey().getValue().toString()))throw new IllegalArgumentException("Этот проект сохранён в другом измерении");
        Building replacement=new Building(session,BuildPlan.parse(saved.source()),this::say);
        cancelRequest();pending.clear();outgoing.clear();if(building!=null)building.stop();
        history.restore(saved);building=replacement;building.mode(history.creative,history.autoGive);startBuilding();
    }
    public void disconnect(){cancelRequest();pending.clear();outgoing.clear();if(history!=null)history.save();if(building!=null)building.stop();building=null;historyKey=null;jumpTicks=0;}
    public void forget(){disconnect();if(history!=null)history.clear();history=null;owner="";}
}
