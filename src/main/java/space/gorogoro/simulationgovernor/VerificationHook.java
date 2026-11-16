package space.gorogoro.simulationgovernor;

import org.bukkit.command.CommandSender;

/*
 * VerificationHook
 * 検証用のビルド(gradlew build -Pverification)にだけ入るコマンドの受け口。
 * 通常のビルドには実装クラスが入らないので、何もしない。
 * @license    GPLv3
 * @copyright  Copyright gorogoro.space 2026
 * @author     kubotan
 * @see        <a href="https://gorogoro.space">Gorogoro Server.</a>
 */
public interface VerificationHook {

  // 検証用のビルドにだけ入る実装クラスの名前
  String IMPLEMENTATION = "space.gorogoro.simulationgovernor.verification.VerificationCommands";

  /**
   * /simgov の検証用のサブコマンドを処理する。
   * @return 処理したら true(知らないサブコマンドなら false)
   */
  boolean onCommand(SimulationGovernor plugin, CommandSender sender, String[] args);
}
