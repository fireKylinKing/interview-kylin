package interview.guide.modules.interview.skill;

import interview.guide.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/interview/skills")
public class InterviewSkillController {

    private final InterviewSkillService skillService;

    public InterviewSkillController(InterviewSkillService skillService) {
        this.skillService = skillService;
    }

    @GetMapping
    public Result<List<InterviewSkillService.SkillDTO>> listSkills() {
        return Result.success(skillService.getAllSkills());
    }

    @GetMapping("/{id}")
    public Result<InterviewSkillService.SkillDTO> getSkill(@PathVariable String id) {
        return Result.success(skillService.getSkill(id));
    }
}
