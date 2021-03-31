package com.unihogsoft.scalag.vulkan;

import org.junit.jupiter.api.Test;

/**
 * @author MarconZet
 * Created 17.04.2020
 */

class VulkanContextTest {

    @Test
    void initializingContextWithNoArguments() {
        VulkanContext context = new VulkanContext();
        context.destroy();
    }

    @Test
    void initializingContextWithValidationLayers() {
        VulkanContext context = new VulkanContext(true);
        context.destroy();
    }

}