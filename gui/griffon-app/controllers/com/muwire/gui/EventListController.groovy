package com.muwire.gui

import griffon.core.artifact.GriffonController
import griffon.core.controller.ControllerAction
import griffon.inject.MVCMember
import griffon.metadata.ArtifactProviderFor
import javax.annotation.Nonnull

@ArtifactProviderFor(GriffonController)
class EventListController {
    @MVCMember @Nonnull
    EventListModel model

    @ControllerAction
    void click() {
        model.clickCount++
    }
}