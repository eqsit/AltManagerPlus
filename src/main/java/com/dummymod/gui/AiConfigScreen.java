package com.dummymod.gui;

import com.dummymod.ai.*;
import com.dummymod.config.DummyConfig;
import com.dummymod.dummy.DummyManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.*;
import net.minecraft.text.Text;

public final class AiConfigScreen extends Screen {
    private final Screen parent;
    private TextFieldWidget whitelist,url,model,key;
    private String error="";
    public AiConfigScreen(Screen parent){super(Text.literal("DummyMod — ИИ-строитель / OmniRoute"));this.parent=parent;}
    private TextFieldWidget field(int y,String placeholder,String value,int max) {
        var f=new TextFieldWidget(textRenderer,width/2-160,y,320,20,Text.literal(placeholder));f.setMaxLength(max);f.setPlaceholder(Text.literal(placeholder));f.setText(value==null?"":value);addDrawableChild(f);return f;
    }
    protected void init() {
        DummyConfig c=DummyConfig.getInstance();AiConnection a=AiConnection.get();int top=32;
        whitelist=field(top+12,"Точные ники через запятую",c.aiChatWhitelist,512);
        url=field(top+48,"URL OmniRoute /v1",a.baseUrl,512);
        model=field(top+84,"Идентификатор модели",a.model,160);
        key=field(top+120,"Новый API-ключ (пусто = оставить сохранённый)","",512);
        // Existing secrets are never drawn into a text field. New values are masked.
        key.addFormatter((text,index)->Text.literal("•".repeat(text.length())).asOrderedText());
        addDrawableChild(ButtonWidget.builder(Text.literal(c.aiEnabled?"ИИ: ВКЛ":"ИИ: ВЫКЛ"),b->{c.aiEnabled=!c.aiEnabled;c.save();b.setMessage(Text.literal(c.aiEnabled?"ИИ: ВКЛ":"ИИ: ВЫКЛ"));}).dimensions(width/2-160,top+146,103,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(c.aiAllowBreaking?"Ломать: ДА":"Ломать: НЕТ"),b->{c.aiAllowBreaking=!c.aiAllowBreaking;c.save();b.setMessage(Text.literal(c.aiAllowBreaking?"Ломать: ДА":"Ломать: НЕТ"));}).dimensions(width/2-53,top+146,103,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(c.aiServerFormattedChat?"Чат сервера: ДА":"Чат сервера: НЕТ"),b->{c.aiServerFormattedChat=!c.aiServerFormattedChat;c.save();b.setMessage(Text.literal(c.aiServerFormattedChat?"Чат сервера: ДА":"Чат сервера: НЕТ"));}).dimensions(width/2+54,top+146,106,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal(c.aiPreferWalking?"Движение: пешком":"Движение: полёт"),b->{c.aiPreferWalking=!c.aiPreferWalking;c.save();b.setMessage(Text.literal(c.aiPreferWalking?"Движение: пешком":"Движение: полёт"));}).dimensions(width/2-160,top+170,156,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Полёт: "+c.aiCreativeFlightSpeed.label),b->{c.aiCreativeFlightSpeed=c.aiCreativeFlightSpeed.next();c.save();b.setMessage(Text.literal("Полёт: "+c.aiCreativeFlightSpeed.label));}).dimensions(width/2+4,top+170,156,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"),b->{
            if(!ChatAccess.validWhitelist(whitelist.getText())){error="Ник: 3–16 латинских букв, цифр или _";return;}
            String oldUrl=a.baseUrl,oldModel=a.model,oldKey=a.apiKey;
            try {
                a.baseUrl=url.getText().trim();a.model=model.getText().trim();if(!key.getText().isBlank())a.apiKey=key.getText().trim();a.save();
                c.aiChatWhitelist=whitelist.getText().trim();c.save();close();
            } catch(Exception e){a.baseUrl=oldUrl;a.model=oldModel;a.apiKey=oldKey;error="Не удалось сохранить. Проверь URL и доступ к файлу.";}
        }).dimensions(width/2-160,top+198,103,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Проекты / память"),b->{if(client!=null)client.setScreen(new SavedBuildsScreen(this));}).dimensions(width/2-53,top+198,106,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"),b->close()).dimensions(width/2+57,top+198,103,20).build());
    }
    public void render(DrawContext ctx,int mx,int my,float delta) {
        super.render(ctx,mx,my,delta);int x=width/2-160;
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer,"Белый список (пусто = ни на кого не реагировать)",x,32,0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer,"URL API",x,68,0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer,"Модель",x,104,0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer,AiConnection.get().ready()?"Ключ сохранён. Введи новый только для замены.":"API-ключ OmniRoute",x,140,0xAAAAAA);
        if(!error.isEmpty())ctx.drawCenteredTextWithShadow(textRenderer,error,width/2,Math.min(268,height-12),0xFFDD77);
        if(height>320){ctx.drawTextWithShadow(textRenderer,"Чат: «НикДамми, построй ...», затем обсуждай ресурсы.",x,285,0xCCCCCC);ctx.drawTextWithShadow(textRenderer,"«летай быстро» / «летай медленно». Память: 300 сообщений.",x,299,0xCCCCCC);}
    }
    public void close(){if(client!=null)client.setScreen(parent);}
}
