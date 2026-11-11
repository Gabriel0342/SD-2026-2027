package tpf;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Member;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class LeaderBot extends ListenerAdapter {

    //Verifica se um membro tem o papel "admin-arquivo".
    private boolean isAdmin(Member member) {
        return member != null && member.getRoles().stream().anyMatch(role -> role.getName().equals("admin-arquivo"));
    }
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.load();
        // Substitua pelo token que copiou do Portal de Desenvolvedores
        String token = dotenv.get("DISCORD_BOT_TOKEN");

        // Inicia a conexão com o Discord
        JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT) // Permite ler mensagens
                .addEventListeners(new LeaderBot())        // Registra esta classe para ouvir eventos
                .build();                                     // Liga o bot
        }

   // Método executado sempre que uma mensagem é enviada no servidor
   @Override
   public void onMessageReceived(MessageReceivedEvent event) {
       // Ignora mensagens enviadas por outros bots (incluindo ele próprio)
       if (event.getAuthor().isBot()) return;

       // Ignora mensagens fora do canal #arquivo
       if (!event.getChannel().getName().equals("arquivo")) return;
   }

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        event.getGuild().updateCommands().addCommands(
                Commands.slash("registar", "Regista um texto no arquivo").addOption(OptionType.STRING, "texto", "Texto a registar", true),
                Commands.slash("estado", "Ver o estado do sistema"),
                Commands.slash("config", "Configura parâmetros do sistema").addOption(OptionType.INTEGER, "r", "Fator de replicação", true),
                Commands.slash("recuperar", "Recupera os dados entre duas datas").addOption(OptionType.STRING, "de", "Data inicial (AAAA-MM-DD)", true).addOption(OptionType.STRING, "ate", "Data final (AAAA-MM-DD)", true),
                Commands.slash("replica-token", "Gera um token de réplica"),
                Commands.slash("replica-remover", "Remove a réplica com um dado id").addOption(OptionType.STRING, "id", "ID da réplica", true),
                Commands.slash("rajada", "Faz uma rajada de n mensagens com intervaloMs").addOption(OptionType.INTEGER, "n", "Número de mensagens", true).addOption(OptionType.INTEGER, "intervaloms", "Intervalo em milissegundos", true)
        ).queue();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getChannel().getName().equals("arquivo")) {
            event.reply("Este comando só funciona no canal #arquivo.").setEphemeral(true).queue();
            return;
        }
        switch (event.getName()) {
            case "registar" -> event.replyFormat("`/registar` recebido: %s — ainda não implementado", event.getOption("texto").getAsString()).queue();
            case "estado" -> event.reply("`/estado` recebido — ainda não implementado").queue();

            case "config" -> {
                if (!isAdmin(event.getMember())) {
                    event.reply("Comando exclusivo a membros com o papel admin-arquivo.").setEphemeral(true).queue();
                    return;
                }
                long r = event.getOption("r").getAsLong();
                if (r <= 0) {
                    event.reply("Erro: r tem de ser um inteiro positivo.").setEphemeral(true).queue();
                    return;
                }
                event.replyFormat("`/config` recebido: r=%d — ainda não implementado", r).queue();
            }

            case "recuperar" -> {
                String de = event.getOption("de").getAsString();
                String ate = event.getOption("ate").getAsString();
                try {
                    LocalDate d1 = LocalDate.parse(de);
                    LocalDate d2 = LocalDate.parse(ate);
                    if (d1.isAfter(d2)) {
                        event.reply("Erro: 'de' tem de ser anterior ou igual a 'ate'.").setEphemeral(true).queue();
                        return;
                    }
                } catch (DateTimeParseException e) {
                    event.reply("Erro: datas inválidas, uso AAAA-MM-DD.").setEphemeral(true).queue();
                    return;
                }
                event.replyFormat("`/recuperar` recebido: de=%s, ate=%s — ainda não implementado", de, ate).queue();
            }

            case "replica-token" -> {
                if (!isAdmin(event.getMember())) {
                    event.reply("Comando exclusivo a membros com o papel admin-arquivo.").setEphemeral(true).queue();
                    return;
                }
                event.reply("`/replica-token` recebido — ainda não implementado").queue();
            }

            case "replica-remover" -> {
                if (!isAdmin(event.getMember())) {
                    event.reply("Comando exclusivo a membros com o papel admin-arquivo.").setEphemeral(true).queue();
                    return;
                }
                event.replyFormat("`/replica-remover` recebido: id=%s — ainda não implementado", event.getOption("id").getAsString()).queue();
            }

            case "rajada" -> {
                if (!isAdmin(event.getMember())) {
                    event.reply("Comando exclusivo a membros com o papel admin-arquivo.").setEphemeral(true).queue();
                    return;
                }
                long n = event.getOption("n").getAsLong();
                long intervalo = event.getOption("intervaloms").getAsLong();
                if (n <= 0 || intervalo < 0) {
                    event.reply("Erro: n tem de ser positivo e intervaloMs não negativo.").setEphemeral(true).queue();
                    return;
                }
                event.replyFormat("`/rajada` recebido: n=%d, intervaloMs=%d — ainda não implementado", n, intervalo).queue();
            }

            default -> event.reply("Comando desconhecido.").queue();
        }
    }
}
