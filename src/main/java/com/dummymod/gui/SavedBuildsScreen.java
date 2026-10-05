package com.dummymod.gui;

import com.dummymod.ai.Conversation;
import com.dummymod.dummy.*;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.Text;
import java.util.*;

public final class SavedBuildsScreen extends Screen {
    private record Entry(PlayerSession session,Conversation.SavedPlan plan){}
    private final Screen parent;
    private final List<Entry> entries=new ArrayList<>();
    private int page;
    private String notice="";
    public SavedBuildsScreen(Screen parent){super(Text.literal("Сохранённые проекты"));this.parent=parent;}
    protected void init() {
        entries.clear();
        for(var session:DummyManager.dummySessions)for(var project:session.ai.savedProjects())entries.add(new Entry(session,project));
        int rows=Math.max(1,(height-132)/28),pages=Math.max(1,(entries.size()+rows-1)/rows);page=Math.min(page,pages-1);
        int left=width/2-160;
        for(int i=page*rows;i<Math.min(entries.size(),(page+1)*rows);i++) {
            Entry e=entries.get(i);int y=66+(i-page*rows)*28;
            String name=e.session.displayName()+": "+e.plan.name();if(name.length()>30)name=name.substring(0,27)+"…";
            if(e.plan.id().equals("current"))name="Текущий: "+name;
            String detail=e.plan.name();
            if(e.plan.source().has("origin")){var origin=e.plan.source().getAsJsonArray("origin");detail+="\nНачало: "+origin.get(0).getAsInt()+" "+origin.get(1).getAsInt()+" "+origin.get(2).getAsInt();}
            if(e.plan.savedAt()>0)detail+="\nСохранён: "+java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(java.time.Instant.ofEpochMilli(e.plan.savedAt()).atZone(java.time.ZoneId.systemDefault()));
            addDrawableChild(ButtonWidget.builder(Text.literal(name),b->{try{e.session.ai.resumeSaved(e.plan.id());close();}catch(Exception ex){notice=ex instanceof IllegalArgumentException?ex.getMessage():"Не удалось восстановить проект";}}).dimensions(left,y,320,20).tooltip(Tooltip.of(Text.literal(detail))).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("←"),b->{page--;clearAndInit();}).dimensions(left,height-60,36,20).build()).active=page>0;
        addDrawableChild(ButtonWidget.builder(Text.literal("→"),b->{page++;clearAndInit();}).dimensions(left+40,height-60,36,20).build()).active=page+1<pages;
        addDrawableChild(ButtonWidget.builder(Text.literal("Очистить чат"),b->{DummyManager.dummySessions.forEach(s->s.ai.forget());notice="Переписка очищена; схемы сохранены";}).dimensions(left+80,height-60,126,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"),b->close()).dimensions(left+210,height-60,110,20).build());
    }
    public void render(DrawContext ctx,int mx,int my,float delta) {
        super.render(ctx,mx,my,delta);
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,14,0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,"Нажми на схему, чтобы продолжить готовую часть.",width/2,34,0xCCCCCC);
        if(entries.isEmpty())ctx.drawCenteredTextWithShadow(textRenderer,"Подключи дамми. Схемы доступны разрешённому нику.",width/2,76,0xCCCCCC);
        if(!notice.isEmpty())ctx.drawCenteredTextWithShadow(textRenderer,notice,width/2,height-32,0xFFDD77);
    }
    public void close(){if(client!=null)client.setScreen(parent);}
}
