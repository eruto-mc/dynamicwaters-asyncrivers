package net.erutobusiness.asyncrivers.mixin;

import net.erutobusiness.asyncrivers.AsyncRivers;
import net.erutobusiness.asyncrivers.MountainRiverScheduler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 先読みが終わるまで参加を断る（2026-08-21 追加）。
 *
 * <p>⚠⚠ <b>なぜ断るのか.</b> 先読みが終わる前に入ると、その人の周りの山岳河川は
 * <b>その場で作られる</b>。サーバスレッドは塞がらない（このMODが外してある）が、
 * <b>地形が来るのを待つ</b>ことになり、症状は「入れたのに地形が来ない」。
 * ⚠ <b>入った人には、それが何なのか分からない</b>——2026-08-20 に同じ見え方で
 * 数分待たされ、原因に辿り着くのに jstack が要った。
 * 黙って待たせるより、理由と残りを伝えて入り直してもらうほうが短く済む。
 *
 * <p>⚠ <b>断るのは「実のある残り」があるときだけ</b>
 * （{@link MountainRiverScheduler#joinShouldWait()}）。記録が温まっている起動では
 * 先読みは1秒ほどで終わるので、そこで断ると邪魔なだけになる。
 *
 * <p>⚠ <b>認証より前で断る.</b> {@code handleHello} は Mojang への問い合わせの前に来るので、
 * 断りが速いうえ、<b>手元から素の接続で確かめられる</b>（正規の資格情報が要らない）。
 *
 * <p>⚠ <b>これは「掘る処理」には効かない.</b> 既に入っている人が未生成の土地へ踏み込むのは
 * 入場では止められない。そちらは<b>ワールドボーダーを生成済みの内側に置く</b>ことで防ぎ
 * （BorderKeeper 1.2.0）、起きたときは記録に1行出す
 * （{@code MountainRiverScheduler#warnIfPlayersOnline}）。
 */
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class LoginGateMixin {

    @Shadow
    public abstract void disconnect(Component reason);

    @Inject(method = "handleHello", at = @At("HEAD"), cancellable = true)
    private void asyncrivers$holdJoinsWhilePreWarming(ServerboundHelloPacket packet, CallbackInfo ci) {
        if (!MountainRiverScheduler.joinShouldWait()) {
            return;
        }
        int left = MountainRiverScheduler.preWarmRemaining();
        int planned = MountainRiverScheduler.preWarmPlanned();
        // ⚠ **断ったことを記録に残す。** 残らないと、部員が弾かれ続けていても
        //    運用している側から見えない（「誰も入ってこない」としか見えない）。
        AsyncRivers.LOGGER.info("Async Rivers: 先読み中なので参加を断った（{} ／ 残り {} / {} リージョン）",
                packet.name(), left, planned);
        disconnect(Component.literal(
                "地形の下ごしらえ中です（残り " + left + " / " + planned + " リージョン）。\n"
                        + "少し待ってから入り直してください。\n"
                        + "⚠ いま入ると、周りの地形が出てくるのを待つことになります。"));
        ci.cancel();
    }
}
