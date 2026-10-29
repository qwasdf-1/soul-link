"""The operator setup chest renders and applies settings through ordinary container clicks."""

from __future__ import annotations

import struct
import threading

from terra_rasa_testkit.protocol import Reader, Writer, component_text


def test_operator_setup_menu_opens_and_changes_settings(server):
    name = "SetupAdmin"
    bot = server.bot(name)
    # In an offline-mode test server the player's profile is known after login; op cannot resolve
    # the bot's deliberately synthetic profile from Mojang's profile service before then.
    server.rcon(f"op {name}")
    opened = threading.Event()
    content_received = threading.Event()
    menu: dict[str, int | str] = {}
    original_handle = bot._handle

    def observe_menu(packet_id: int, reader: Reader) -> None:
        packet_name = bot._ids.clientbound_name(bot.state.phase, packet_id)
        if bot.state.phase == "play" and packet_name == "open_screen":
            menu["container_id"] = reader.varint()
            reader.varint()  # menu type registry id
            menu["title"] = component_text(reader.nbt())
            opened.set()
        elif bot.state.phase == "play" and packet_name == "container_set_content":
            container_id, state_id = reader.varint(), reader.varint()
            if container_id == menu.get("container_id"):
                menu["state_id"] = state_id
                content_received.set()
        original_handle(packet_id, reader)

    # The shared headless bot intentionally ignores inventory packets. Observe only the two header
    # fields needed to send a standards-compliant click, leaving the rest of the testkit unchanged.
    bot._handle = observe_menu
    bot.command("soullink setup")

    assert opened.wait(10), "the server did not open a vanilla inventory screen"
    assert menu["title"] == "Soul Link Setup"
    assert content_received.wait(10), "the server did not send the setup inventory contents"

    click = (Writer()
             .varint(int(menu["container_id"]))
             .varint(int(menu["state_id"]))
             .raw(struct.pack(">hb", 10, 0))  # Shared vitality toggle, left click
             .varint(0)  # ContainerInput.PICKUP
             .varint(0)  # no changed slots
             .boolean(False)  # empty carried stack
             .bytes())
    bot._send("play", "container_click", click)

    bot.wait_for_message("Shared vitality off from the next run.", timeout=10)
    assert "next run off" in server.rcon("soullink status")
